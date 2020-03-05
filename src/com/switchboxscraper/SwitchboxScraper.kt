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
import org.apache.commons.io.IOUtils
import java.io.File
import java.nio.charset.Charset
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
        val p = ProcessBuilder("dot", "-Kfdp", "-Goverlap=scale", "-Gsplines=line", "-Tsvg", "-O", "${file}").start()
    }

    private fun clusterPIPGraphNode(icNode: PIPGraphNode, clusters: MutableMap<GRJunctionType, MutableList<PIPGraphNode>>) {
        var dir : GlobalRouteDir
        var type = PJType.UNCLASSIFIED
        val wn = icNode.pip.wireName

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
            clusters[grjType] = mutableListOf()
        }

        clusters[grjType]?.add(icNode)
    }

    private fun createInterconnectGraph(ic: Interconnect, query: GraphQuery): MutableGraph {
        // Create graph nodes for each pip junction class selected for this graph
        var icNodes = ic.pipJunctions
                .filter { pj -> ic.pjClassification[pj]?.pjClass in query.classes }
                .fold(mutableMapOf<Wire, MutableNode>()) { acc2, pj ->
                    acc2[pj] = createPJGraphNode(ic, pj); acc2
                }

        // Cluster interconnect based on inferred position in the switchbox
        var nodeClusters = mutableMapOf<GRJunctionType, MutableList<PIPGraphNode>>()
        icNodes.map { clusterPIPGraphNode(PIPGraphNode(it), nodeClusters) }

        // Remove any excluded clusters and the nodes of these clusters
        val excludedClusters = nodeClusters.filter { it.key in query.excludes }
        var excludedNodes = excludedClusters.entries.fold(mutableListOf<MutableNode>()) { nodes, excludedCluster ->
            excludedCluster.value.map {
                nodes.add(it.node)
            }
            nodes
        }
        icNodes.entries.removeAll { it.value in excludedNodes }
        nodeClusters.keys.removeAll { it in excludedClusters.keys }

        // Create graphs for each cluster
        var graphClusters = mutableMapOf<GRJunctionType, MutableGraph>()
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
                        val to = mutNode(icNodes[it.endWire]?.name()) // Copy node name
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
