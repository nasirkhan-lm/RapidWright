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