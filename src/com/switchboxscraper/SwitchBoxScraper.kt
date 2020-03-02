package com.switchboxscraper

import com.xilinx.rapidwright.device.Device
import com.xilinx.rapidwright.device.Tile
import com.xilinx.rapidwright.device.Wire

import guru.nidi.graphviz.attribute.Color
import guru.nidi.graphviz.attribute.Label
import guru.nidi.graphviz.attribute.Shape
import guru.nidi.graphviz.attribute.Style
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.model.Factory.mutGraph
import guru.nidi.graphviz.model.Factory.mutNode
import guru.nidi.graphviz.model.MutableGraph
import guru.nidi.graphviz.model.MutableNode


import java.io.File
import java.util.*

enum class PJClass {
    CLK,
    ELEC,
    ROUTING
}

enum class PJType {
    SOURCE,
    SINK,
    BUF,
    INTERNAL
}

enum class PJRoutingType {
    GLOBAL,
    CLB,
    INTERNAL
}

class PJProperty {
    var pjClass = PJClass.CLK
    var routingType = PJRoutingType.GLOBAL
    var pjType = PJType.SOURCE
}

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

    fun getPJClass(wire: Wire): PJClass {
        val wn = wire.wireName
        when {
            wn.contains("CLK") -> return PJClass.CLK
            wn.contains("VCC") || wn.contains("GND") -> return PJClass.ELEC
            else -> return PJClass.ROUTING
        }
    }

    fun pjOfClass(t: PJClass) = pipJunctions.filter { pj -> getPJClass(pj) == t }

    fun getPJType(pj: Wire): PJType {
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

    fun getPJRoutingType(pj: Wire): PJRoutingType {
        return PJRoutingType.GLOBAL
    }

    fun classifyPJ(pj: Wire): PJProperty {
        var prop = PJProperty()
        prop.pjClass = getPJClass(pj)
        prop.pjType = getPJType(pj)
        prop.routingType = getPJRoutingType(pj)
        return prop
    }
}

class SwitchBoxScraper {
    var device: Device
    var interconnects = mutableListOf<Interconnect>()

    constructor(deviceName: String) {
        device = Device.getDevice(deviceName)
    }

    fun scrape(tiles: List<String>) {
        tiles.map { name -> println("Scraping Switchox: ${name}"); interconnects.add(Interconnect(device.getTile(name))) }

        val graphTypes = listOf(EnumSet.of(PJClass.ELEC), EnumSet.of(PJClass.ROUTING), EnumSet.of(PJClass.CLK), EnumSet.allOf(PJClass::class.java))
        var dotFiles = graphTypes.fold(mutableListOf<String>()) { files, type -> files.add(createDotGraph(type)); files }

        println("Wrote files:")
        (dotFiles + dotsToSvg(dotFiles)).map { v -> println("\t${v}") }
    }

    fun createPJGraphNode(ic: Interconnect, wire: Wire): MutableNode {
        var node = mutNode(wire.wireName)
        val prop = ic.pjClassification[wire]
        // Color classification by PIP junction class
        when (prop?.pjClass) {
            PJClass.ELEC -> node.add(Color.RED).add(Color.RED2.fill())
            PJClass.CLK -> node.add(Color.YELLOW).add(Color.YELLOW2.fill())
            else -> node.add(Color.BLACK).add(Color.GREY.fill())
        }

        // Shape classification by PIP junction type
        when (prop?.pjType) {
            PJType.SOURCE -> node.add(Shape.RECTANGLE)
            PJType.SINK -> node.add(Shape.DIAMOND)
        }

        return node
    }

    fun dotsToSvg(files: List<String>): List<String> {
        files.map { f -> Runtime.getRuntime().exec("dot -Ksfdp -Tsvg -Goverlap=scale -O ${f}") }
        return files.map { file -> file + ".svg" }
    }

    fun createDotGraph(includes: EnumSet<PJClass>): String {
        val includeString = includes.fold("") { str, v -> str + v }
        println("\tCreating graph for PIPs of type(s): ${includeString}")

        // For each switchbox, create graph nodes for each pip junction class selected for this graph
        var interconnectNodes = interconnects.fold(mutableMapOf<Interconnect, Map<Wire, MutableNode>>()) { acc, ic ->
            acc[ic] = ic.pipJunctions.filter { pj -> ic.pjClassification[pj]?.pjClass in includes }.fold(mutableMapOf<Wire, MutableNode>()) { acc2, pj ->
                acc2[pj] = createPJGraphNode(ic, pj); acc2
            }
            acc
        }

        // Create directed links between all created graph nodes and their destination (Forward) PIPs also present in
        // the graph.
        interconnectNodes.map { intcNodes ->
            intcNodes.value.map { pjn ->
                pjn.key.forwardPIPs.filter { pip -> intcNodes.value.containsKey(pip.endWire) }.map { pip -> pjn.value.addLink(intcNodes.value[pip.endWire]) }
            }
        }

        // Create graphs and add all created nodes,
        var interconnectGraphs = mutableListOf<MutableGraph>()
        interconnectNodes.map{ intcNodes ->
            var g = mutGraph(intcNodes.key.name)
                    .setDirected(true)
                    .setCluster(true)
                    .nodeAttrs()
                    .add(Style.FILLED)
                    .graphAttrs().add(Color.RED)
                    .graphAttrs().add(Label.of(intcNodes.key.name))
            intcNodes.value.map { node -> g.add(node.value) }
            interconnectGraphs.add(g)
        }


        // Add all Interconnect graphs to a single graph and write the graph
        var parentGraph = mutGraph("Parent").setDirected(true)
        interconnectGraphs.map { ig -> ig.addTo(parentGraph) }

        val fileName = "graphs/sb_${includeString}.dot"
        return Graphviz.fromGraph(parentGraph).render(Format.DOT).toFile(File(fileName)).absolutePath
    }
}

fun main(args: Array<String>) {
    val sbs = SwitchBoxScraper("xc7a35t")
    sbs.scrape(listOf("INT_R_X41Y61", "CLBLM_R_X41Y61"))
}