package com.switchboxscraper

import com.xilinx.rapidwright.device.Device
import com.xilinx.rapidwright.device.Wire
import guru.nidi.graphviz.attribute.*
import guru.nidi.graphviz.attribute.Rank
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

enum class GraphType { Interconnect, ClusterConn, ClusterSS }

data class DotFileToRender(val filename: String, val engine: String)

data class GraphQuery(
        var tiles: List<String>,
        var classes: EnumSet<PJClass>,
        var excludes: List<GRJunctionType>) {

    override fun toString(): String {
        var str = ""
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
        dotsToSvg(createDotGraph(query))
    }

    private fun createPJGraphNode(ic: Interconnect, wire: Wire): MutableNode {
        val node = mutNode(ic.name + ":" + wire.wireName)
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

    private fun dotsToSvg(files: List<DotFileToRender>) {
        println("Writing SVG files: '${files}...")
        files.map { file ->
            val p = ProcessBuilder("dot", "-K${file.engine}", "-Goverlap=scale", "-Gsplines=line", "-Tsvg", "-O", "${file.filename}").start().waitFor()
        }
    }

    private fun createClusterSSGraph(ic: Interconnect, query: GraphQuery, cqr: ClusterQueryResult): MutableGraph {
        // Create graph nodes for each cluster
        var clusters = cqr.types
                .filter { it !in query.excludes }
                .fold(mutableMapOf<GRJunctionType, MutableNode>()) { acc, type ->
                    val clusterName = ic.name + ":" + type.toString()
                    acc[type] = mutNode(clusterName)
                    acc
                }

        // Cluster together source and sink nodes
        val sourceGraph = mutGraph(ic.name + ": Source")
                .setDirected(true)
                .setCluster(true)
                .graphAttrs().add(Color.RED)
                .graphAttrs().add(Label.of(ic.name + ": Source"))
                .graphAttrs().add(Rank.dir(Rank.RankDir.TOP_TO_BOTTOM))
        clusters.filter { it.key.type == PJType.SOURCE }.map { it.value.addTo(sourceGraph) }
        val sinkGraph = mutGraph(ic.name + ": Sink")
                .setDirected(true)
                .setCluster(true)
                .graphAttrs().add(Color.GREEN)
                .graphAttrs().add(Label.of(ic.name + ": Sink"))
                .graphAttrs().add(Rank.dir(Rank.RankDir.TOP_TO_BOTTOM))
        clusters.filter { it.key.type == PJType.SINK }.map { it.value.addTo(sinkGraph) }

        // Add source and sink clusters to a top-level graph
        val parentGraph = mutGraph(ic.name)
                .setDirected(true)
                .setCluster(true)
                .graphAttrs().add(Color.RED)
                .graphAttrs().add(Label.of(ic.name))
                .graphAttrs().add(Rank.dir(Rank.RankDir.TOP_TO_BOTTOM))
        sourceGraph.addTo(parentGraph)
        sinkGraph.addTo(parentGraph)

        // Create connections between each cluster
        cqr.connections.map {
            val from = mutNode(clusters[it.key]?.name()) // Copy node name
            it.value.map {
                val to = mutNode(clusters[it]?.name()) // Copy node name
                from.links().add(from.linkTo(to as LinkTarget))
            }
            parentGraph.add(from)
        }

        return parentGraph
    }


    private fun createClusterConnectivityGraph(ic: Interconnect, query: GraphQuery, cqr: ClusterQueryResult): MutableGraph {
        // Create graph nodes for each cluster
        var clusters = cqr.types
                .filter { it !in query.excludes }
                .fold(mutableMapOf<GRJunctionType, MutableNode>()) { acc, type ->
                    val clusterName = ic.name + ":" + type.toString()
                    acc[type] = when (type.type) {
                        PJType.SOURCE -> mutNode(clusterName).add(Shape.RECTANGLE).add(Color.GREEN)
                        PJType.SINK -> mutNode(clusterName).add(Shape.DIAMOND).add(Color.BLUE)
                        else -> mutNode(clusterName)
                    }
                    acc
                }

        // Add all clusters to a top-level graph
        val parentGraph = mutGraph(ic.name)
                .setDirected(true)
                .setCluster(true)
                .graphAttrs().add(Color.RED)
                .graphAttrs().add(Label.of(ic.name))
        clusters.map { it.value.addTo(parentGraph) }

        // Create connections between each cluster
        cqr.connections.map {
            val from = mutNode(clusters[it.key]?.name()) // Copy node name
            it.value.map {
                val to = mutNode(clusters[it]?.name()) // Copy node name
                from.links().add(from.linkTo(to as LinkTarget))
            }
            parentGraph.add(from)
        }

        return parentGraph
    }

    private fun createInterconnectGraph(ic: Interconnect, query: GraphQuery, iqr: JunctionQueryResult): MutableGraph {
        // Create graph nodes for each PIP junction
        var pipJunctionNodes = iqr.pipJunctions
                .filter { pj -> ic.pjClassification[pj]?.pjClass in query.classes }
                .fold(mutableMapOf<Wire, MutableNode>()) { acc2, pj ->
                    acc2[pj] = createPJGraphNode(ic, pj); acc2
                }

        // Create graphs for each cluster
        var graphClusters = mutableMapOf<GRJunctionType, MutableGraph>()
        iqr.clusters.map {
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
                    .filter { it.endWire in iqr.pipJunctions }
                    .map {
                        val to = mutNode(pipJunctionNodes[it.endWire]?.name()) // Copy node name
                        from.links().add(from.linkTo(to as LinkTarget))
                    }
            parentGraph.add(from)
        }

        return parentGraph
    }

    private fun createDotGraph(query: GraphQuery): List<DotFileToRender> {
        // For each switchbox, create graph nodes for each pip junction class selected for this graph
        var graphs = mutableMapOf<GraphType, MutableMap<Interconnect, MutableGraph>>()
        GraphType.values().map { graphs[it] = mutableMapOf() }


        interconnects.fold(graphs) { acc, ic ->
            var results = ic.processQuery(query)

            acc[GraphType.Interconnect]!![ic] = createInterconnectGraph(ic, query, results.junctionResult)
            acc[GraphType.ClusterConn]!![ic] = createClusterConnectivityGraph(ic, query, results.clusterResult)
            acc[GraphType.ClusterSS]!![ic] = createClusterSSGraph(ic, query, results.clusterResult)
            acc
        }

        // For each graph type Add all graphs to a single parent graph and write the graph
        var graphFiles = mutableListOf<DotFileToRender>()
        graphs.map {
            val parentGraph = mutGraph("Parent").setDirected(true)
            it.value.map { it.value.addTo(parentGraph) }

            val graphName = "sb_" + it.key.toString() + "_" + query.toString()
            val fileName = "graphs/${graphName}.dot"
            Graphviz.fromGraph(parentGraph).render(Format.DOT).toFile(File(fileName)).absolutePath

            val engine = when (it.key) {
                GraphType.Interconnect -> "fdp" // Interconnect is rendered with an undirected engine, due to its possible size
                GraphType.ClusterSS -> "dot"
                GraphType.ClusterConn -> "circo"
                else -> "fdp"
            }
            graphFiles.add(DotFileToRender(fileName, engine))
        }

        return graphFiles
    }
}

fun main(args: Array<String>) {
    val sbs = SwitchboxScraper("xc7a35t")

    sbs.scrape(GraphQuery(
            /*Interconnects:*/  listOf("INT_R_X41Y61"),
            /*Classes:*/        EnumSet.allOf(PJClass::class.java),
            /*Excludes:*/       listOf(GRJunctionType(GlobalRouteDir.UNCLASSIFIED, PJType.UNCLASSIFIED))
    )
    )

    sbs.scrape(GraphQuery(
            /*Interconnects:*/  listOf("INT_R_X41Y61"),
            /*Classes:*/        EnumSet.allOf(PJClass::class.java),
            /*Excludes:*/       listOf()
    )
    )

    sbs.scrape(GraphQuery(
            /*Interconnects:*/  listOf("INT_R_X41Y61", "INT_L_X40Y61"),
            /*Classes:*/        EnumSet.allOf(PJClass::class.java),
            /*Excludes:*/       listOf(GRJunctionType(GlobalRouteDir.UNCLASSIFIED, PJType.UNCLASSIFIED))
    )
    )

    return

}
