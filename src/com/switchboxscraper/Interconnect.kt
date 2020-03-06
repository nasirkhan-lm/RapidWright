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

enum class GlobalRouteDir { EE, WW, SS, NN, NE, NW, SE, SW, UNCLASSIFIED }

data class GRJunctionType(val dir: GlobalRouteDir, val type: PJType)

data class PJProperty(val pjClass: PJClass, val pjType: PJType, val routingType: PJRoutingType)

data class InterconnectResources(val pipJunctions : Set<Wire>, val clusters : Map<GRJunctionType, Set<Wire>>)

class Interconnect(tile: Tile) {
    var pipJunctions = mutableSetOf<Wire>()
    var pjClassification = mutableMapOf<Wire, PJProperty>()
    var name: String = ""

    init {
        name = tile.name
        // Extract all pip junctions by iterating over all PIPs in the tile and collecting the pip start/stop wires
        // which are defined as the pip junctions
        tile.getPIPs().fold(pipJunctions) { junctions, pip -> junctions.addAll(listOf(pip.startWire, pip.endWire)); junctions }
        // Perform PIP junction classification
        pipJunctions.fold(pjClassification) { acc, pj -> acc[pj] = classifyPJ(pj); acc }
    }

    fun filterQuery(query: GraphQuery) : InterconnectResources {
        var pipJunctions = pipJunctions.toMutableSet()  // Copy PIP junctions of the interconnect

        // Cluster PIP Junctions based on inferred position in the switch box
        var pipJunctionClusters = mutableMapOf<GRJunctionType, MutableSet<Wire>>()
        pipJunctions.map { clusterPIPJunction(it, pipJunctionClusters) }

        // Remove any excluded clusters and the PIP junctions of these clusters in our list of pip junctions included
        // in this graph
        val excludedClusters = pipJunctionClusters.filter { it.key in query.excludes }
        var excludedNodes = excludedClusters.entries.fold(mutableListOf<Wire>()) { nodes, excludedCluster ->
            excludedCluster.value.map {nodes.add(it) }; nodes
        }
        pipJunctions.removeAll { it in excludedNodes }
        pipJunctionClusters.keys.removeAll { it in excludedClusters.keys }

        return InterconnectResources(pipJunctions, pipJunctionClusters)
    }

    private fun clusterPIPJunction(pj: Wire, clusters: MutableMap<GRJunctionType, MutableSet<Wire>>) {
        var dir : GlobalRouteDir
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

        if(dir != GlobalRouteDir.UNCLASSIFIED) {
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