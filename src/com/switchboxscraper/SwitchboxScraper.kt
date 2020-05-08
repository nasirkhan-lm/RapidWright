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
import org.json.JSONObject
import java.io.File
import java.lang.NullPointerException
import java.util.*


data class PIPGraphNode(val pip: Wire, val node: MutableNode) {
    constructor(v: Map.Entry<Wire, MutableNode>) : this(v.key, v.value) // Implement construction from a Map key-value pair
}

data class JunctionGraphCluster(val type: GRJunctionType, val graph: MutableGraph) {
    constructor(v: Map.Entry<GRJunctionType, MutableGraph>) : this(v.key, v.value)
}

enum class GraphType { Interconnect, ClusterConn, ClusterSS }

data class DotFileToRender(val filename: String, val engine: String, val extraProps : List<String>)

data class GraphResult(var graph : MutableGraph, var root : MutableNode?)

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

    /**
     * Scrapes the switchbox and executes some user-defined function as a query
     */
    fun scrapeWithFunc(query : GraphQuery, f : (gq: GraphQuery, ss: SwitchboxScraper) -> Unit) {
        query.tiles.map { name -> println("Scraping Switchbox: ${name}");interconnects.add(Interconnect(device.getTile(name))) }
        f(query, this)
    }

    fun scrape(query: GraphQuery) {
        query.tiles.map { name -> println("Scraping Switchbox: ${name}");interconnects.add(Interconnect(device.getTile(name))) }
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
            val extraProps = file.extraProps.fold(""){acc, prop -> acc + "-G" + prop + " "}
            val p = ProcessBuilder("dot", "-K${file.engine}", "-Goverlap=scale", "-Gsplines=line", extraProps, "-Tsvg", "-O", "${file.filename}").start().waitFor()
        }
    }

    private fun createClusterSSGraph(ic: Interconnect, query: GraphQuery, cqr: ClusterQueryResult): GraphResult {
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

        return GraphResult(parentGraph, null)
    }


    private fun createClusterConnectivityGraph(ic: Interconnect, query: GraphQuery, cqr: ClusterQueryResult): GraphResult {
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

        // If an unclassified group exists within the connected graph ,set it as a root node
        val rootNode =  clusters[GRJunctionType(GlobalRouteDir.UNCLASSIFIED, PJType.UNCLASSIFIED)]

        return GraphResult(parentGraph, rootNode)
    }

    private fun createInterconnectGraph(ic: Interconnect, query: GraphQuery, iqr: JunctionQueryResult): GraphResult {
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

        return GraphResult(parentGraph, null)
    }

    private fun createDotGraph(query: GraphQuery): List<DotFileToRender> {
        // For each switchbox, create graph nodes for each pip junction class selected for this graph
        var graphs = mutableMapOf<GraphType, MutableMap<Interconnect, GraphResult>>()
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
            it.value.map { it.value.graph.addTo(parentGraph) }

            val graphName = "sb_" + it.key.toString() + "_" + query.toString()
            val fileName = "graphs/${graphName}.dot"
            Graphviz.fromGraph(parentGraph).render(Format.DOT).toFile(File(fileName)).absolutePath

            val engine = when (it.key) {
                GraphType.Interconnect -> "fdp" // Interconnect is rendered with an undirected engine, due to its possible size
                GraphType.ClusterSS -> "dot"
                GraphType.ClusterConn -> "circo"
                else -> "fdp"
            }

            // Extra properties
            val extraProps = when(it.key) {
                GraphType.ClusterConn -> {
                    // Locate a root node
                    var root : MutableNode? = null
                    for(g in it.value) {
                        if(g.value.root != null){
                            root = g.value.root
                            break
                        }
                    }
                    if(root != null) {
                        listOf("root=\"${root.name()}\"")
                    } else {
                        listOf()
                    }
                }
                else -> listOf()
            }

            graphFiles.add(DotFileToRender(fileName, engine, extraProps))
        }

        return graphFiles
    }
}

fun compareSwitchboxes(gq: GraphQuery, ss: SwitchboxScraper) {
    val sb1 = ss.interconnects[0]
    val sb2 = ss.interconnects[1]

    var sb1_wires = sb1.pipJunctions.fold(mutableSetOf<String>()){acc, wire -> acc.add(wire.wireName); acc }
    var sb2_wires = sb2.pipJunctions.fold(mutableSetOf<String>()){acc, wire -> acc.add(wire.wireName); acc }

    var symdiff = (sb1_wires - sb2_wires).union(sb2_wires - sb1_wires)

    if(symdiff.isEmpty()) {
        println("Switchboxes wire names are equal.")
    } else {
        println("Switchboxes are unequal. Differring wires:")
        symdiff.map { wirename -> println("\t ${wirename}") }
    }
}

fun countClusters(gq: GraphQuery, ss: SwitchboxScraper) {
    var wirenames = ss.interconnects[0].pipJunctions.fold(mutableListOf<String>()){acc, wire -> acc.add(wire.wireName); acc }

    wirenames.sort()

    data class ClusterCount(var count: MutableList<String>, val reg: Regex)
    var clusterTypes = mutableListOf<ClusterCount>()

    // Create Regexes for each class
    for (dir in GlobalRouteDir.values()) {
        for (io in listOf("BEG", "END")) {
            var reg = (dir.toString() + "[0-9]*" + io + ".*").toRegex();
            clusterTypes.add(ClusterCount(mutableListOf(), reg))
        }
    }

    var unmatchedWires = mutableSetOf<String>()

    // Match names to classes
    for (wire in wirenames) {
        var matched = false
        for(clusterClass in clusterTypes) {
            if(clusterClass.reg.matches(wire)){
                clusterClass.count.add(wire)
                matched = true
                break
            }
        }

        if(!matched) {
            // Unmatched wire
            unmatchedWires.add(wire)
        }
    }

    println("Wire counts:")
    clusterTypes.map { println("\t${it.reg.toString()} : ${it.count.size} ${it.count}") }

    println("\nUnmatched wires:")
    println(unmatchedWires)
}


/**
 * For each global routing direction, counts the number of wires of each length type
 */
fun analyzeWireLength(gq: GraphQuery, ss: SwitchboxScraper) {
    var mapForIC = mutableMapOf<String, MutableMap<Point, Int>>()
    for (interconnect in ss.interconnects) {
        /**
         * The direction map will contain the count of wires for each possible XY wire length available
         */
        var directionMap = mutableMapOf<Point, Int>()

        for (wire in interconnect.globalPipJunctions) {
            val direction = interconnect.wireSpan(wire)
            if(direction == null)
                continue

            if (!directionMap.containsKey(direction))
                directionMap[direction] = 0

            directionMap[direction] = directionMap[direction]!! + 1
        }
        mapForIC[interconnect.name] = directionMap
    }

    val title = ss.interconnects.fold(mutableListOf<String>()){ acc, it -> acc.add(it.name);
        acc
    }.joinToString(separator="_")


            // Dump to file
        File(title + "_wiredump.json").writeText(JSONObject(mapForIC).toString())
}

fun main(args: Array<String>) {
    val sbs = SwitchboxScraper("xc7a35t")

    // Get a cluster count for each connection type
    sbs.scrapeWithFunc(GraphQuery(
            /*Interconnects:*/  listOf("INT_R_X23Y109","INT_L_X22Y109"),
            /*Classes:*/        EnumSet.allOf(PJClass::class.java),
            /*Excludes:*/       listOf()
    ), ::analyzeWireLength
    )

    return

    // Compare name equality of two switchboxes. The following two connects to 1: a BRAM and 2: a CLB
    sbs.scrapeWithFunc(GraphQuery(
            /*Interconnects:*/  listOf("INT_R_X41Y60", "INT_R_X37Y22"),
            /*Classes:*/        EnumSet.allOf(PJClass::class.java),
            /*Excludes:*/       listOf()
    ), ::compareSwitchboxes
    )

    if(false) {
        sbs.scrapeWithFunc(GraphQuery(
                /*Interconnects:*/  listOf("INT_R_X41Y60", "INT_R_X41Y61"),
                /*Classes:*/        EnumSet.allOf(PJClass::class.java),
                /*Excludes:*/       listOf()
        ), ::analyzeWireLength
        )

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
}
