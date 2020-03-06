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


data class PIPGraphNode(val pip: Wire, val node: MutableNode) {
    constructor(v: Map.Entry<Wire, MutableNode>) : this(v.key, v.value) // Implement construction from a Map key-value pair
}

data class JunctionGraphCluster(val type: GRJunctionType, val graph: MutableGraph) {
    constructor(v: Map.Entry<GRJunctionType, MutableGraph>) : this(v.key, v.value)
}

data class GraphQuery(
        var tiles: List<String>,
        var classes: EnumSet<PJClass>,
        var excludes: List<GRJunctionType>) {

    override fun toString(): String {
        var str = "sb_"
        tiles.map { str += it + "," }
        str += "_INCLUDES:"
        classes.map { str += it.toString() }
        if (excludes.isNotEmpty()) {
            str += "_EXCLUDES:"
            excludes.map { str += it.dir.toString() + "-" + it.type.toString() + "," }
        }
        return str
    }
}


class SwitchboxScraper(deviceName: String) {
    var device: Device = Device.getDevice(deviceName)
    var interconnects = mutableListOf<Interconnect>()

    fun scrape(query: GraphQuery) {
        query.tiles.map { name -> println("Scraping Switchbox: ${name}"); interconnects.add(Interconnect(device.getTile(name))) }
        dotToSvg(createDotGraph(query))
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

    private fun dotToSvg(file: String) {
        println("Writing SVG file: '${file}.svg'...")
        val p = ProcessBuilder("dot", "-Kfdp", "-Goverlap=scale", "-Gsplines=line", "-Tsvg", "-O", "${file}").start().waitFor()
    }

    private fun createInterconnectGraph(ic: Interconnect, query: GraphQuery): MutableGraph {
        var icResources = ic.filterQuery(query)

        // Create graph nodes for each PIP junction
        var pipJunctionNodes = icResources.pipJunctions
                .filter { pj -> ic.pjClassification[pj]?.pjClass in query.classes }
                .fold(mutableMapOf<Wire, MutableNode>()) { acc2, pj ->
                    acc2[pj] = createPJGraphNode(ic, pj); acc2
                }

        // Create graphs for each cluster
        var graphClusters = mutableMapOf<GRJunctionType, MutableGraph>()
        icResources.clusters.map {
            val clusterName = ic.name + " " + it.key.toString()
            val color = when (it.key.type) {
                PJType.SOURCE -> Color.ORANGE
                PJType.SINK -> Color.GREEN
                else -> Color.BLACK
            }
            val g = mutGraph(clusterName)
                    .setDirected(true)
                    .setCluster(true)
                    .nodeAttrs()
                    .add(Style.FILLED)
                    .graphAttrs().add(color)
                    .graphAttrs().add(Label.of(clusterName))
            // Add all nodes contained within this cluster
            it.value.map { g.add(pipJunctionNodes[it]) }
            graphClusters[it.key] = g
        }

        // Add all clusters to a top-level graph of this interconnect
        val parentGraph = mutGraph(ic.name)
                .setDirected(true)
                .setCluster(true)
                .graphAttrs().add(Color.RED)
                .graphAttrs().add(Label.of(ic.name))
        graphClusters.map { it.value.addTo(parentGraph) }

        // Create directed links between all created graph nodes and their destination (Forward) PIPs also present in
        // the graph.
        pipJunctionNodes.map { pjn ->
            // @note: This is a bit hacky;
            // The links must be explicitly added to the parent graph instead of adding links to the nodes themselves.
            // It has been found, that if links are created on the nodes themselves, this will merge the linked nodes
            // into the subgraph which a node is included in. As a result, duplicate nodes will exist in the graph.
            // To circumvent this, for each connection, we create a pair of nodes equally named with the actual nodes
            // in the graph. As such, when adding a link between these nodes to the parent graph, the link will be
            // defined in the parent graph instead of within the subgraph, avoiding node duplication.
            val from = mutNode(pjn.value.name()) // Copy node name
            pjn.key.forwardPIPs
                    .filter { it.endWire in icResources.pipJunctions }
                    .map {
                        val to = mutNode(pipJunctionNodes[it.endWire]?.name()) // Copy node name
                        from.links().add(from.linkTo(to as LinkTarget))
                    }
            parentGraph.add(from)
        }

        return parentGraph
    }

    private fun createDotGraph(query: GraphQuery): String {
        val graphName = query.toString()
        println("\tCreating graph for PIPs of type(s): ${graphName}")

        // For each switchbox, create graph nodes for each pip junction class selected for this graph
        val icGraphs = interconnects.fold(mutableMapOf<Interconnect, MutableGraph>()) { acc, ic ->
            acc[ic] = createInterconnectGraph(ic, query)
            acc
        }

        // Add all Interconnect graphs to a single graph and write the graph
        val parentGraph = mutGraph("Parent").setDirected(true)
        icGraphs.map { it.value.addTo(parentGraph) }

        val fileName = "graphs/${graphName}.dot"
        return Graphviz.fromGraph(parentGraph).render(Format.DOT).toFile(File(fileName)).absolutePath
    }
}

fun main(args: Array<String>) {
    val sbs = SwitchboxScraper("xc7a35t")
    var query : GraphQuery

    query = GraphQuery(
            /*Interconnects:*/  listOf("INT_R_X41Y61", "CLBLM_R_X41Y61"),
            /*Classes:*/        EnumSet.allOf(PJClass::class.java),
            /*Excludes:*/       listOf(GRJunctionType(GlobalRouteDir.UNCLASSIFIED, PJType.UNCLASSIFIED))
    )
    sbs.scrape(query)

    query = GraphQuery(
            /*Interconnects:*/  listOf("INT_R_X41Y61", "CLBLM_R_X41Y61"),
            /*Classes:*/        EnumSet.allOf(PJClass::class.java),
            /*Excludes:*/       listOf()
    )
    sbs.scrape(query)

}
