package com.switchboxscraper

import com.xilinx.rapidwright.device.Tile
import com.xilinx.rapidwright.device.Wire
import java.lang.Exception

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

data class Point(val x : Int, val y : Int)
fun getDirection(p1 : Point, p2 : Point) : GlobalRouteDir {
    // Check rectilinear
    if(p1.x == p2.x) {
        return if(p1.y < p2.y) GlobalRouteDir.NN else GlobalRouteDir.SS;
    }else if (p1.y == p2.y) {
        return if(p1.x > p2.x) GlobalRouteDir.WW else GlobalRouteDir.EE;
    }

    // Check diagonal
    if(p1.x > p2.x){
        return if(p1.y < p2.y) GlobalRouteDir.NW else GlobalRouteDir.SW;
    } else {
        return if(p1.y < p2.y) GlobalRouteDir.NE else GlobalRouteDir.SE;
    }
}

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

    // List of all pip junctions classified by their directionality
    var dirGlobalPipJunctions = mutableMapOf<GlobalRouteDir, MutableSet<Wire>>()

    var pjClassification = mutableMapOf<Wire, PJProperty>()
    var name: String = ""
    var tile: Tile = tile;

    init {
        name = tile.name

        gatherPipJunctions()
        gatherGlobalPipJunctions()
        classifyGlobalPipJunctionDir()

        // Perform PIP junction classification
        pipJunctions.fold(pjClassification) { acc, pj -> acc[pj] = classifyPJ(pj); acc }
    }

    fun gatherPipJunctions() {
        // Extract all pip junctions by iterating over all PIPs in the tile and collecting the pip start/stop wires
        // which are defined as the pip junctions. Only gather routable pips
        for( pip in tile.getPIPs()) {
            for (wire in listOf(pip.startWire, pip.endWire)){
                if(getPJClass(wire) == PJClass.ROUTING) {
                    pipJunctions.add(wire);
                }
            }
        }
    }

    fun gatherGlobalPipJunctions() {
        // A global pip junction will be located by determining whether the source (for END junctions) or sink
        // (for BEG junctions) are located in this tile, or in an external tile
        for(wire in pipJunctions){
            val node = wire.node
            var candidateExternalTile = getExternalTile(wire)

            if (candidateExternalTile != null) {
                // Are we logically still on the same tile? This would be the case if the node terminated in ie. the CLB
                // connected to the switchbox, which will have the same coordinates
                val x = candidateExternalTile.tileXCoordinate
                val y = candidateExternalTile.tileYCoordinate

                if(x == tile.tileXCoordinate && y == tile.tileYCoordinate){
                    continue
                }

                globalPipJunctions.add(wire)
            }
        }
    }

    /**
     * Given @param wire, a wire on the border of this switchbox, returns the tile of which the node of this wire
     * connects to.
     */
    fun getExternalTile(wire : Wire): Tile? {
        var node = wire.node
        var externalTile: Tile? = null
        if (wire.startWire == wire) {

            if (node.allDownhillPIPs.isEmpty())
                println("?")

            // This is a source wire. Identify sink wires by iterating over the downhill PIPs and sanity checking that
            // they all terminate in the same tile. The downhill PIPs corresponds to the *outputs* within the pip
            // junction of the *end* switchbox which this wire connects to

            // Filter any pips which are located in the current tile. These may be bounces into the tile
            var externalPips = node.allDownhillPIPs.filter { it.tile != tile }

            if(externalPips.isEmpty())
                return null // Cannot be an external tile; all pips are internal (possibly a FAN wire)

            // Get a candidate external tile which we check others against
            externalTile = externalPips[0].tile

            // Next, we want to ensure that all other downhill pips are located in the same external tile
            val pipsInSameTile = externalPips.all { it.tile == externalTile }

            if (wire.wireName.contains("NL1BEG0")) {
                // println("")
            }

            if (!pipsInSameTile) {
                println("Wire ${wire.toString()} fanned out to pips located in different external tiles!")
                for (pip in externalPips) {
                    if (tile.getTileManhattanDistance(pip.tile) > tile.getTileManhattanDistance(externalTile)) {
                        externalTile = pip.tile
                    }
                }
                println("Selected tile furthest away: ${externalTile!!.name}\n")
            }

        } else {
            // This is a sink wire. The source wire will be the base wire of the associated node
            externalTile = node.tile
        }

        if (externalTile == this.tile){
            // This implies that both sourc and sink of the wire was within the current tile.
            // In this case - even if the wires appears in the global routing network, we ignore the connection.
            // (Where ignoring implies that we count the wire as being part of the internal routing network of the
            // switchbox).
            return null
        }

        return externalTile
    }

    /**
     * Analyses the connectivity of each Pip junction onto the global routing network, to determine the direction
     * of the incoming wire (from the global routing network) into the PIP junction.
     */
    fun classifyGlobalPipJunctionDir() {
        for(pip in globalPipJunctions) {
            var externalTile = getExternalTile(pip)

            if(externalTile == null)
                continue

            // External tile detected. Get the direction between the two tiles
            val p1 = Point(tile.tileXCoordinate, tile.tileYCoordinate)
            val p2 = Point(externalTile.tileXCoordinate, externalTile.tileYCoordinate)

            val dir = getDirection(p1, p2)

            if (dirGlobalPipJunctions[dir] == null){
                dirGlobalPipJunctions[dir] = mutableSetOf<Wire>()
            }
            dirGlobalPipJunctions[dir]?.add(pip)
        }
    }

    fun wireLength(wire : Wire): Int? {
        val externalTile = getExternalTile(wire) ?: return null
        return tile.getTileManhattanDistance(externalTile)
    }

    fun wireSpan(wire : Wire): Point? {
        val externalTile = getExternalTile(wire) ?: return null
        val p1 = Point(tile.tileXCoordinate, tile.tileYCoordinate)
        val p2 = Point(externalTile.tileXCoordinate, externalTile.tileYCoordinate)

        return Point(p2.x - p1.x, p2.y - p1.y)
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
