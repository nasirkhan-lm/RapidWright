package com.switchboxscraper

import com.xilinx.rapidwright.device.Device
import com.xilinx.rapidwright.device.Wire

import guru.nidi.graphviz.attribute.Color
import guru.nidi.graphviz.attribute.Label
import guru.nidi.graphviz.attribute.Shape
import guru.nidi.graphviz.attribute.Style
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.model.Factory.mutGraph
import guru.nidi.graphviz.model.Factory.mutNode
import guru.nidi.graphviz.model.LinkTarget
import guru.nidi.graphviz.model.MutableGraph
import guru.nidi.graphviz.model.MutableNode

import java.io.File
import java.util.*

data class PIPGraphNode (val pip : Wire, val node : MutableNode)

class SwitchboxScraper(deviceName: String) {
    var device: Device = Device.getDevice(deviceName)
    var interconnects = mutableListOf<Interconnect>()

    fun scrape(tiles: List<String>) {
        tiles.map { name -> println("Scraping Switchox: ${name}"); interconnects.add(Interconnect(device.getTile(name))) }

        val graphTypes = listOf(EnumSet.of(PJClass.ROUTING), EnumSet.of(PJClass.CLK), EnumSet.allOf(PJClass::class.java))
        val dotFiles = graphTypes.fold(mutableListOf<String>()) { files, type -> files.add(createDotGraph(type)); files }

        println("Wrote files:")
        (dotFiles + dotsToSvg(dotFiles)).map { v -> println("\t${v}") }
    }

    private fun createPJGraphNode(ic: Interconnect, wire: Wire): MutableNode {
        val node = mutNode(wire.wireName)
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

    private fun dotsToSvg(files: List<String>): List<String> {
        files.map { f -> Runtime.getRuntime().exec("dot -Kfdp -Goverlap=scale -Gsplines=line -Tsvg -O ${f}") }
        return files.map { file -> file + ".svg" }
    }

    private fun clusterPIPGraphNode(icNode: PIPGraphNode, clusters: MutableMap<GRJunctionType, MutableList<PIPGraphNode>>) {
        var dir = GlobalRouteDir.EE
        var type = PJType.SOURCE
        val wn = icNode.pip.wireName

        // Deduce direction
        when {
            wn.startsWith("EE") -> dir = GlobalRouteDir.EE
            wn.startsWith("NN") -> dir = GlobalRouteDir.NN
            wn.startsWith("SS") -> dir = GlobalRouteDir.SS
            wn.startsWith("WW") -> dir = GlobalRouteDir.WW
            wn.startsWith("NE") -> dir = GlobalRouteDir.NE
            wn.startsWith("NW") -> dir = GlobalRouteDir.NW
            wn.startsWith("SE") -> dir = GlobalRouteDir.SE
            wn.startsWith("SW") -> dir = GlobalRouteDir.SW
            else -> dir = GlobalRouteDir.INVALID
        }

        // Deduce type
        when {
            wn.contains("BEG") -> type = PJType.SINK
            wn.contains("END") -> type = PJType.SOURCE
            else -> PJType.INTERNAL // unhandled
        }

        val grjType = GRJunctionType(dir, type)
        if(!clusters.containsKey(grjType))
            clusters.put(grjType, mutableListOf<PIPGraphNode>())

        clusters[grjType]?.add(icNode)

    }

    private fun createInterconnectGraph(ic: Interconnect, includes: EnumSet<PJClass>): MutableGraph {
        // Create graph nodes for each pip junction class selected for this graph
        val icNodes = ic.pipJunctions
                .filter { pj -> ic.pjClassification[pj]?.pjClass in includes }
                .fold(mutableMapOf<Wire, MutableNode>()) { acc2, pj ->
                    acc2[pj] = createPJGraphNode(ic, pj); acc2
                }

        // Cluster interconnect based on inferred position in the switchbox
        val nodeClusters = mutableMapOf<GRJunctionType, MutableList<PIPGraphNode>>()
        icNodes.map { icNode ->
            var node = PIPGraphNode(icNode.key, icNode.value)
            clusterPIPGraphNode(node, nodeClusters) }


        // Create graphs for each cluster
        var clusterGraphs = mutableMapOf<GRJunctionType, MutableGraph>()
        nodeClusters.map {
            val clusterName = ic.name + " " + it.key.toString()
            val g = mutGraph(clusterName)
                    .setDirected(true)
                    .setCluster(true)
                    .nodeAttrs()
                    .add(Style.FILLED)
                    .graphAttrs().add(Color.RED)
                    .graphAttrs().add(Label.of(clusterName))
            it.value.map { pipGraphNode -> g.add(pipGraphNode.node) }
            clusterGraphs[it.key] = g
        }

        // Add all clusters to a top-level graph of this interconnect
        val parentGraph = mutGraph(ic.name)
                .setDirected(true)
                .setCluster(true)
                .graphAttrs().add(Color.RED)
                .graphAttrs().add(Label.of(ic.name))
        clusterGraphs.map { it.value.addTo(parentGraph) }

        // Create directed links between all created graph nodes and their destination (Forward) PIPs also present in
        // the graph.
        icNodes.map { pjn ->
            // @note: This is a bit hacky;
            // The links must be explicitely added to the parent graph instead of adding links to the nodes themselves.
            // It has been found, that if links are created on the nodes themselves, this will merge the linked nodes
            // into the subgraph which a node is included in. As a result, duplicate nodes will exist in the graph.
            // To circumvent this, for each connection, we create a pair of nodes equally named with the actual nodes
            // in the graph. As such, when adding a link between these nodes to the parent graph, the link will be
            // defined in the parent graph instead of within the subgraph, avoiding node duplication.
            val from = mutNode(pjn.value.name()) // Copy node name
            pjn.key.forwardPIPs
                    .filter { pip -> icNodes.containsKey(pip.endWire) }
                    .map {
                        val to =  mutNode(icNodes[it.endWire]?.name()) // Copy node name
                        from.links().add(from.linkTo(to as LinkTarget))
                    }
            parentGraph.add(from)
        }

        return parentGraph
    }

    private fun createDotGraph(includes: EnumSet<PJClass>): String {
        val includeString = includes.fold("") { str, v -> str + v }
        println("\tCreating graph for PIPs of type(s): ${includeString}")

        // For each switchbox, create graph nodes for each pip junction class selected for this graph
        val icGraphs = interconnects.fold(mutableMapOf<Interconnect, MutableGraph>()) { acc, ic ->
            acc[ic] = createInterconnectGraph(ic, includes)
            acc
        }

        // Add all Interconnect graphs to a single graph and write the graph
        val parentGraph = mutGraph("Parent").setDirected(true)
        icGraphs.map { it.value.addTo(parentGraph) }

        val fileName = "graphs/sb_${includeString}.dot"
        return Graphviz.fromGraph(parentGraph).render(Format.DOT).toFile(File(fileName)).absolutePath
    }
}

fun main(args: Array<String>) {
    val sbs = SwitchboxScraper("xc7a35t")
    sbs.scrape(listOf("INT_R_X41Y61", "CLBLM_R_X41Y61"))
}