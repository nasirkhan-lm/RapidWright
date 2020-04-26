package com.switchboxscraper

import com.xilinx.rapidwright.device.Tile
import com.xilinx.rapidwright.device.Wire

enum class PJClass {
    CLK,
    ELEC,
    ROUTING
}

enum class PJType {
    SOURCE,
    SINK,
    BUF,
    INTERNAL,
    UNCLASSIFIED
}

enum class PJRoutingType {
    GLOBAL,
    CLB,
    INTERNAL,
    UNCLASSIFIED
}

enum class GlobalRouteDir { EE, WW, SS, NN, NE, NW, SE, SW, SR, SL, EL, ER, WL, WR, NL, NR, UNCLASSIFIED }
data class GRJunctionType(val dir: GlobalRouteDir, val type: PJType)
data class PJProperty(val pjClass: PJClass, val pjType: PJType, val routingType: PJRoutingType)

data class JunctionQueryResult(val pipJunctions: Set<Wire>, val clusters: Map<GRJunctionType, Set<Wire>>)
// A directed graph of the connectivity of each GRJunctionType
data class ClusterQueryResult(val types: Set<GRJunctionType>, val connections: Map<GRJunctionType, Set<GRJunctionType>>)

// Collection of each type of query result
data class InterconnectQueryResult(val junctionResult: JunctionQueryResult, val clusterResult: ClusterQueryResult)

class Interconnect(tile: Tile) {
    // List of all pip junctions in the switchbox
    var pipJunctions = mutableSetOf<Wire>()
    // List of all pip junctions in the switchbox switch begin or end a global routing wire
    var globalPipJunctions = mutableSetOf<Wire>()

    var pjClassification = mutableMapOf<Wire, PJProperty>()
    var name: String = ""
    var tile: Tile = tile;

    init {
        name = tile.name

        gatherPipJunctions()
        gatherGlobalPipJunctions()

        // Perform PIP junction classification
        pipJunctions.fold(pjClassification) { acc, pj -> acc[pj] = classifyPJ(pj); acc }
    }

    fun gatherPipJunctions() {
        // Extract all pip junctions by iterating over all PIPs in the tile and collecting the pip start/stop wires
        // which are defined as the pip junctions
        tile.getPIPs().fold(pipJunctions) { junctions, pip -> junctions.addAll(listOf(pip.startWire, pip.endWire)); junctions }
    }

    fun gatherGlobalPipJunctions() {
        // A global pip junction will be located by determining whether the source (for END junctions) or sink
        // (for BEG junctions) are located in this tile, or in an external tile
        pipJunctions.fold(globalPipJunctions) {
            acc, pip ->
                val checker = fun() {
                    val node = pip.node
                    var candidateExternalTile: Tile? = null

                    // Check if a global routing wire TERMINATES in this switchbox
                    if (node.tile != tile) {
                        // the node of the wire has its base wire in a tile that is not this tile; hence it must be a global wire
                        candidateExternalTile = node.tile
                    }
                    // Check if this wire is the START of a global routing wire
                    else {

                        for (nodeWire in node.allWiresInNode) {
                            if (nodeWire.tile != tile) {
                                candidateExternalTile = nodeWire.tile
                                break
                            }
                        }
                    }

                    if (candidateExternalTile != null) {
                        // Are we logically still on the same tile? This would be the case if the node terminated in ie. the CLB
                        // connected to the switchbox, which will have the same coordinates
                        val x = candidateExternalTile.tileXCoordinate
                        val y = candidateExternalTile.tileYCoordinate

                        if(x == tile.tileXCoordinate && y == tile.tileYCoordinate){
                            return
                        }

                        acc.add(pip)
                    }

                    return
                }
            checker()
            ; acc
        }
    }

    fun processQuery(query: GraphQuery): InterconnectQueryResult {
        // --------------------------------------------------------------------
        // Step 1: Process interconnect
        // --------------------------------------------------------------------
        var pipJunctions = pipJunctions.toMutableSet()  // Copy PIP junctions of the interconnect

        // Cluster PIP Junctions based on inferred position in the switch box
        var pipJunctionClusters = mutableMapOf<GRJunctionType, MutableSet<Wire>>()
        var pipJunctionToCluster = mutableMapOf<Wire, GRJunctionType>() // Convenience map for looking up the cluster of a PIP junction
        pipJunctions.map { clusterPIPJunction(it, pipJunctionClusters, pipJunctionToCluster) }

        // Remove any excluded clusters and the PIP junctions of these clusters in our list of pip junctions included
        // in this graph
        val excludedClusters = pipJunctionClusters.filter { it.key in query.excludes }
        var excludedNodes = excludedClusters.entries.fold(mutableListOf<Wire>()) { nodes, excludedCluster ->
            excludedCluster.value.map { nodes.add(it) }; nodes
        }
        pipJunctions.removeAll { it in excludedNodes }
        pipJunctionClusters.keys.removeAll { it in excludedClusters.keys }

        val junctionResult = JunctionQueryResult(pipJunctions, pipJunctionClusters)

        // --------------------------------------------------------------------
        // Step 2: Process cluster connectivity
        // --------------------------------------------------------------------
        var clusterConnectivity = mutableMapOf<GRJunctionType, MutableSet<GRJunctionType>>()
        // Initialize graph
        pipJunctionClusters.map {
            clusterConnectivity[it.key] = mutableSetOf()
        }

        val clusterTypes = pipJunctionClusters.keys
        // For each cluster type
        pipJunctionClusters.map { cluster ->
            // For each PIP Junction within the cluster
            cluster.value.map { pipj ->
                // For each forward PIP
                pipj.forwardPIPs.map {pip ->
                    if(pipJunctions.contains(pip.endWire)) {
                        val connectsToCluster = pipJunctionToCluster[pip.endWire]!!
                        clusterConnectivity[cluster.key]?.add(connectsToCluster)
                    }
                }
            }
        }
        val clusterResult = ClusterQueryResult(clusterTypes, clusterConnectivity)

        // --------------------------------------------------------------------
        // Step 3: Return results
        // --------------------------------------------------------------------
        return InterconnectQueryResult(junctionResult, clusterResult)
    }

    private fun clusterPIPJunction(pj: Wire, clusters: MutableMap<GRJunctionType,
            MutableSet<Wire>>, reverseClusters: MutableMap<Wire, GRJunctionType>) {
        var dir: GlobalRouteDir
        var type = PJType.UNCLASSIFIED
        val wn = pj.wireName

        // Deduce direction
        dir = when {
            wn.startsWith("EE") -> GlobalRouteDir.EE
            wn.startsWith("NN") -> GlobalRouteDir.NN
            wn.startsWith("SS") -> GlobalRouteDir.SS
            wn.startsWith("WW") -> GlobalRouteDir.WW
            wn.startsWith("NE") -> GlobalRouteDir.NE
            wn.startsWith("NW") -> GlobalRouteDir.NW
            wn.startsWith("SE") -> GlobalRouteDir.SE
            wn.startsWith("SW") -> GlobalRouteDir.SW
            else -> GlobalRouteDir.UNCLASSIFIED
        }

        if (dir != GlobalRouteDir.UNCLASSIFIED) {
            // Deduce type
            type = when {
                wn.contains("BEG") -> PJType.SINK
                wn.contains("END") -> PJType.SOURCE
                else -> PJType.UNCLASSIFIED // unhandled
            }
        }

        val grjType = GRJunctionType(dir, type)
        if (!clusters.containsKey(grjType)) {
            clusters[grjType] = mutableSetOf()
        }

        clusters[grjType]?.add(pj)
        reverseClusters[pj] = grjType
    }

    private fun getPJClass(wire: Wire): PJClass {
        val wn = wire.wireName
        return when {
            wn.contains("CLK") -> PJClass.CLK
            wn.contains("VCC") || wn.contains("GND") -> PJClass.ELEC
            else -> PJClass.ROUTING
        }
    }

    private fun getPJType(pj: Wire): PJType {
        val bkPips = pj.backwardPIPs
        val fwPips = pj.forwardPIPs
        when {
            bkPips.size == 1 && fwPips.size == 1 -> return PJType.BUF
            fwPips.size == 0 -> return PJType.SINK
            bkPips.size == 0 -> return PJType.SOURCE
            fwPips.size > 1 && bkPips.size > 1 -> return PJType.INTERNAL
            else -> assert(false)
        }
        return PJType.SOURCE
    }

    private fun getPJRoutingType(pj: Wire) = PJRoutingType.GLOBAL

    private fun classifyPJ(pj: Wire) = PJProperty(getPJClass(pj), getPJType(pj), getPJRoutingType(pj))
}
