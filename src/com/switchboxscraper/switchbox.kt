package com.switchboxscraper

import com.xilinx.rapidwright.device.Tile
import com.xilinx.rapidwright.device.Wire
import com.xilinx.rapidwright.device.Device
import com.xilinx.rapidwright.device.Node
import org.json.JSONObject
import java.util.*
import kotlin.math.abs


enum class Channel {
    Hz, Vt, Local
}

enum class Direction { N,E,S,W,NE,NW,SE,SW,Local }

fun getNodeDirection(node : Node) : Direction {
    /**
     * Node::AllWiresInNode is sorted as follows:
     * [input wire, output wires, intermediate wires]
     * We extract the input wire and first output wire and use the difference in tile positions to determine the
     * direction of the node.
     */
    val t1 = node.allWiresInNode[0].tile
    val t2 = node.allWiresInNode[1].tile

    val t1x = t1.tileXCoordinate
    val t1y = t1.tileYCoordinate
    val t2x = t2.tileXCoordinate
    val t2y = t2.tileYCoordinate

    // Check local
    if(t1x == t2x && t1y == t2y) {
        return Direction.Local
    }

    // Check rectilinear
    if(t1x == t2x) {
        return if(t1y < t2y) Direction.N else Direction.S
    }else if (t1y == t2y) {
        return if(t1x > t2x) Direction.W else Direction.E
    }

    // Check diagonal
    return if(t1x > t2x){
        if(t1y < t2y) Direction.NW else Direction.SW
    } else {
        if(t1y < t2y) Direction.NE else Direction.SE
    }
}

class Switchbox(tile: Tile, debug: Boolean = true) {
    // List of all included pip junctions in the switchbox
    var pipJunctions = mutableSetOf<Wire>()
    // List of excluded pip junctions in the switchbox
    var excludedPipJunctions = mutableSetOf<Wire>()
    // List of all pip junctions which are inputs to the switchbox
    var inPipJunctions = mutableSetOf<Wire>()
    // List of all pip junctions which are outputs of the switchbox
    var outPipJunctions = mutableSetOf<Wire>()

    // List of all pip junctions which are bidirectional (both input and output of the switchbox)
    var bidirPipJunctions = mutableSetOf<Wire>()

    // List of all pip junctions classified by their directionality
    var dirGlobalPipJunctions = mutableMapOf<GlobalRouteDir, MutableSet<Wire>>()

    var name: String = ""
    var tile: Tile = tile

    val multidropLongWires = listOf<String>("LV9", "LH6", "LV_L9")


    init {
        name = tile.name
        gatherPipJunctions()

        if (debug) {
            printPipJunctions()
        }

    }

    fun isSwitchboxTile(tile: Tile): Boolean {
        return "INT_L_" in tile.name || "INT_R_" in tile.name
    }

    /**
     * nodeToAdjacenctWireList
     * Transforms a node into an ordered list of wires and their adjacent wires. First item in the list is the starting
     * (driving) wire of @p node. The adjacent wires will only be the forward wire(s) of a wire. The function will
     * consider wires between switchboxes - as such, all wires crossing over IP/CLBs etc. are disregarded
     */
    fun nodeToAdjacenctWireList(node: Node): MutableMap<Wire, MutableSet<Wire>> {
        fun isAdjacentTiles(t1: Tile, t2: Tile): Boolean {
            val t1x = t1.tileXCoordinate
            val t1y = t1.tileYCoordinate
            val t2x = t2.tileXCoordinate
            val t2y = t2.tileYCoordinate

            return (abs(t1x - t2x) == 1 && abs(t1y - t2y) == 0) ||
                    (abs(t1x - t2x) == 0 && abs(t1y - t2y) == 1)
        }

        /**
         * Note: this is not equivalent to using comparison (==) operator between tiles. We consider tiles to be equal
         * if their <tile> coordinates are equal. This is ie. the case with adjacent CLB and SB tiles. This counting
         * method is due to the fact that we count in hops, ie. switchboxes spanned.
         */
        fun isEqualTiles(t1: Tile, t2: Tile): Boolean {
            val t1x = t1.tileXCoordinate
            val t1y = t1.tileYCoordinate
            val t2x = t2.tileXCoordinate
            val t2y = t2.tileYCoordinate

            return t1x == t2x && t1y == t2y
        }

        val aw = node.allWiresInNode
        val adp = node.allDownhillPIPs

        /**
         * Node::AllWiresInNode is sorted as follows:
         * [input wire, output wires, intermediate wires]
         * Extract these three subset of wires for later use:
         */
        // 1: Filter the input wire
        var intermediateWireList = node.allWiresInNode.toMutableList()
        val driveWire = intermediateWireList.removeAt(0)
        var intermediateWires = intermediateWireList.toMutableSet()

        // 2: Filter the output wires. There is no way to ask RapidWright for this info directly.
        // We do this by finding the unique wires of downhill PIPs of this node.
        // We do an extra check on whether the tile of the startwire is not the tile of the driving wire of the node.
        // This is to properly handle cases such as seen in ie. FAN_BOUNCE2, which both has an output to an adjacent tile
        // but also PIPs internally in the switchbox
        var outputWires = node.allDownhillPIPs.fold(mutableSetOf<Wire>()) {
            acc, pip ->
            if (pip.startWire.tile != driveWire.tile)
                acc.add(pip.startWire)
            acc
        }

        intermediateWires = (intermediateWires - outputWires).toMutableSet()


        // We only care about wires in switchboxes, since the tile coordinates of the FPGA is defined as switchbox
        // positions.
        var wiresInSBs = node.allWiresInNode.filter { isSwitchboxTile(it.tile) }
        intermediateWires = (intermediateWires intersect wiresInSBs).toMutableSet()

        /**
         * Next, we collect the wires which corresponds to hops between switchboxes.
         * We filter anything but the 1st output wire from considered connection wires. The 2nd output wire (in multidrop)
         * wires needs to be specially handled in terms of connecting to the closest adjacent wire (which may be one
         * of the intermediate wires OR the 1st output wire).
         */
        var nodeHopMap = mutableMapOf<Wire, MutableSet<Wire>>()

        var otherOutputs = outputWires.filterIndexed {index, _ -> index > 0}


        // Sort wires in SB such that outputwires are considered at the end
        var sortedWires = (wiresInSBs - outputWires).toMutableList()
        sortedWires.addAll(outputWires)

        var wireStack = mutableListOf<Wire>()
        wireStack.add(driveWire)
        var consideredWires = mutableSetOf<Wire>()

        while (wireStack.isNotEmpty()) {
            var w1 = wireStack.removeAt(0)
            nodeHopMap[w1] = mutableSetOf<Wire>()
            for (w2 in wiresInSBs) {
                if (w1 == w2) {
                    continue
                } else {
                    if (isAdjacentTiles(w1.tile, w2.tile)) {
                        // The nodes are adjacent. Ensure that we only keep forward connections between the nodes
                        // (ie. we don't want the list to be doubly linked).
                        if (!(nodeHopMap.containsKey(w2) && nodeHopMap[w2]!!.contains(w1))) {
                            nodeHopMap[w1]!!.add(w2)
                            if(!nodeHopMap.containsKey(w2)) {
                                wireStack.add(0, w2)
                            }
                        }
                    }
                }
            }
        }

        /**
         * Cleanup/ensure integrity of the wire.
         * The cleanup stage ensures that any given wire is only referenced by a single other wire. If multiple
         * wires reference a single wire as an output wire, the wire in the closest tile will be selected as the
         * referring wire.
         */
        for(w in nodeHopMap.keys) {
            var referringWires = mutableSetOf<Wire>()

            for(w2 in nodeHopMap.keys){
                if(nodeHopMap[w2]?.contains(w)!!) {
                    referringWires.add(w2)
                }
            }

            if(referringWires.size == 1)
                break

            // Multiple referring wires. Find closest wire
            var candidateConnectionWire : Wire? = null
            for (w2 in referringWires) {
                if(isEqualTiles(w.tile, w2.tile)) {
                    // Choose the wire - no better wire can be found
                    candidateConnectionWire = w2
                    break
                } else if(isAdjacentTiles(w.tile, w2.tile)) {
                    candidateConnectionWire = w2
                }
            }

            // Remove connections from wires which are not the closest wire
            for(w2 in referringWires){
                if(w2 == candidateConnectionWire)
                    continue
                nodeHopMap[w2]!!.remove(w)
            }
        }

        /*
        /**
         * Finally, we connect the output wires. We connect output wires based on the wire which resides in the nearest
         * Tile. It may be possible that an output wire connects to a wire in the same tile (ie. see NN6BEG0 - in this
         * case we assume that the multidrop connects to the parallel wire in the same tile).
         */

        for(outw in otherOutputs){
            var candidateConnectionWire : Wire? = null
            for (w in nodeHopMap.keys) {
                if(isEqualTiles(outw.tile, w.tile)) {
                    // Choose the wire - no better wire can be found
                    candidateConnectionWire = w
                    break
                } else if(isAdjacentTiles(outw.tile, w.tile)) {
                    candidateConnectionWire = w
                }
            }

            if(candidateConnectionWire == null) {
                throw Exception("Could not connect output wire!")
            }

            nodeHopMap[candidateConnectionWire]!!.add(outw)

        }
         */

        if(outputWires.size > 1) {
            println("!")
        }

        return nodeHopMap
    }

    /*
        analyseNodeChannelContribution
        Traverses all wires of @p node, counting the direction of each wire wrt. whether it is horizontally or vertically
        directed.
        Since we cannot simply ask a wire which direction it is, we have to recreate the directivity of a wire through
        checking which wires are adjacent wrt. tile coordinates.
    */
    fun analyseNodeChannelContribution(node: Node) : MutableMap<String, Int> {
        fun connectionChannel(w1: Wire, w2: Wire): Channel {
            val t1x = w1.tile.tileXCoordinate
            val t1y = w1.tile.tileYCoordinate
            val t2x = w2.tile.tileXCoordinate
            val t2y = w2.tile.tileYCoordinate

            return if (t1x == t2x && t1y == t2y) {
                Channel.Local
            } else if (t1y == t2y) {
                Channel.Hz
            } else {
                Channel.Vt
            }
        }

        var wireList = nodeToAdjacenctWireList(node)

        var hzWires = 0
        var vtWires = 0

        for ((w1, adjw) in wireList) {
            for (w2 in adjw) {
                val channel = connectionChannel(w1, w2)
                if (channel == Channel.Hz) {
                    hzWires++
                } else if (channel == Channel.Vt){
                    vtWires++
                }
            }
        }

        val info = mutableMapOf<String, Int>()

        info["Hz"] = hzWires
        info["Vt"] = vtWires
        info["length"] = hzWires + vtWires

        return info
    }

    fun gatherPipJunctions() {
        // Extract all pip junctions by iterating over all PIPs in the tile and collecting the pip start/stop wires
        // which are defined as the pip junctions. Only gather routable pips
        for (pip in tile.getPIPs()) {
            val isRoutingPJ = getPJClass(pip.startWire) == PJClass.ROUTING || getPJClass(pip.endWire) == PJClass.ROUTING

            if (isRoutingPJ) {
                if (pip.isBidirectional) {
                    bidirPipJunctions.addAll(setOf(pip.startWire, pip.endWire))
                } else {
                    inPipJunctions.add(pip.startWire)
                    outPipJunctions.add(pip.endWire)
                }
            } else {
                excludedPipJunctions.addAll(setOf(pip.startWire, pip.endWire))
            }
        }
        inPipJunctions = (inPipJunctions - bidirPipJunctions).toMutableSet()
        outPipJunctions = (outPipJunctions - bidirPipJunctions).toMutableSet()
        pipJunctions = inPipJunctions.union(outPipJunctions).union(bidirPipJunctions).toMutableSet()
    }


    private fun getPJClass(wire: Wire): PJClass {
        val wn = wire.wireName
        return when {
            wn.contains("CLK") -> PJClass.CLK
            wn.contains("VCC") || wn.contains("GND") -> PJClass.ELEC
            else -> PJClass.ROUTING
        }
    }

    fun printPipJunctions() {
        println("Found # pipjunctions:\t" + (pipJunctions.count() + excludedPipJunctions.count()).toString())
        println("Excluded #:\t\t" + excludedPipJunctions.count().toString())
        println("Considering #:\t" + pipJunctions.count().toString())
        println("Inputs: ")
        inPipJunctions.map { print(it.wireName + ", ") }
        println("\nOutputs: ")
        outPipJunctions.map { print(it.wireName + ", ") }
        println("\nBidirs: ")
        bidirPipJunctions.map { print(it.wireName + ", ") }
        println("\nExcluded: ")
        excludedPipJunctions.map { print(it.wireName + ", ") }
        println("\n")
    }


    fun getWireExternalWireTerminations(wire : Wire): List<Wire> {
        var externalWires = mutableSetOf<Wire>()
        var node = wire.node
        var externalTile: Tile? = null
        var allWires = node.allWiresInNode
        if (wire.startWire == wire) {

            if (node.allDownhillPIPs.isEmpty())
                println("?")

            var dhp = node.allDownhillPIPs

            // This is a source wire. Identify sink wires by iterating over the downhill PIPs and sanity checking that
            // they all terminate in the same tile. The downhill PIPs corresponds to the *outputs* within the pip
            // junction of the *end* switchbox which this wire connects to
            for (pip in node.allDownhillPIPs) {
                if(pip.startWire.tile.tileXCoordinate != tile.tileXCoordinate || pip.startWire.tile.tileYCoordinate != tile.tileYCoordinate) {
                    externalWires.add(pip.startWire)
                }
            }
        } else {
            for(multidropW in multidropLongWires){
                if (wire.wireName.contains(multidropW)){
                    // This is a special case which cant seem to be handled properly by the default rapidwright functions.
                    // This is the middle of a long wire wherein the long wire drives this PIP junction, but the junction
                    // is unidirectional.
                    // In this case, we want to add both possible drivers (both ends of the long wire) as external wires
                    // these two wires are always the first two of the allWiresInNode struct
                    externalWires.add(node.allWiresInNode[0])
                    externalWires.add(node.allWiresInNode[1])
                    return externalWires.toList()
                }
            }


            // This is a sink wire. The source wire will be the base wire of the associated node
            externalWires.add(node.allWiresInNode[0])
        }

        return externalWires.toList()
    }

    /**
     * Gather all required information for the Netcracker data format, and translate to the appropriate string
     * format
     */
    fun dumpToNetcracker(): JSONObject {
        data class NetcrackerPipJunctionConn (val name : String, val x : Int, val y : Int);
        data class NetcrackerPipJunction (var name : String,
                                          var backwards_pjs : MutableList<NetcrackerPipJunctionConn> = mutableListOf(),
                                          var forward_pjs : MutableList<NetcrackerPipJunctionConn> = mutableListOf())

        var pjs = mutableListOf<NetcrackerPipJunction>()

        for(pj in pipJunctions) {

            var npj = NetcrackerPipJunction(name = pj.wireName)


            // If we identify that a PIP Junction connects to a long wire, the below logic will not manage to include
            // the input/output global wire, and need to manually account for this
            var isLongWirePJ = false

            if(pj.forwardPIPs.isNotEmpty()) {
                for (pip in pj.forwardPIPs) {
                    var endTile = pip.endWire.tile
                    if(pip.isBidirectional) {
                        // Add the end of the PIP which is -not- this pip junction
                        var w  = pip.endWire
                        if (pip.endWire == pj) {
                            w = pip.startWire
                        }
                        npj.forward_pjs.add(NetcrackerPipJunctionConn(name = w.wireName, x = endTile.tileXCoordinate, y = endTile.tileYCoordinate))
                        isLongWirePJ = true
                    } else {
                        npj.forward_pjs.add(NetcrackerPipJunctionConn(name = pip.endWireName, x = endTile.tileXCoordinate, y = endTile.tileYCoordinate))
                    }
                }
            }
            if(pj.forwardPIPs.isEmpty()
                    // Also catch bounce wires from BYP and FAN junctions
                    || """BOUNCE[0-9]""".toRegex().containsMatchIn(pj.wireName)) {
                for(wire in getWireExternalWireTerminations(pj)) {
                    var endTile = wire.tile
                    npj.forward_pjs.add(NetcrackerPipJunctionConn(name = wire.wireName, x = endTile.tileXCoordinate, y = endTile.tileYCoordinate))
                }
            }

            if(pj.backwardPIPs.isNotEmpty()){
                for(pip in pj.backwardPIPs) {
                    var startTile = pip.startWire.tile
                    if(pip.isBidirectional) {
                        // Disregard the PIP, it has already been counted as a forward pip and will be counted in the
                        // other direction once the PJ of the other end of the bidirectional PIP is being dumped.
                        continue
                    } else {
                        npj.backwards_pjs.add(NetcrackerPipJunctionConn(name = pip.startWireName, x = startTile.tileXCoordinate, y = startTile.tileYCoordinate))
                    }
                }
            } else {
                if(!pj.wireName.contains("LOGIC_OUTS")) { // LOGIC_OUTS are handled separately
                    for(wire in getWireExternalWireTerminations(pj)) {
                        var startTile = wire.tile
                        npj.backwards_pjs.add(NetcrackerPipJunctionConn(name = wire.wireName, x = startTile.tileXCoordinate, y = startTile.tileYCoordinate))
                    }
                }
            }

            if(isLongWirePJ) {
                // This is a long wire; wires are bidirectional and may have multiple drops.
                // However, to keep things uniform, we will select the PJ the furthest away from this tile to be the
                // connection which we keep for further analysis

                var externalWires = mutableSetOf<Wire>()
                for (pip in pj.node.allDownhillPIPs) {
                    if(pip.startWire.tile != tile) {
                        externalWires.add(pip.startWire)
                    }
                }

                // Find furthest away PJ
                var w : Wire? = null
                for(wire in externalWires) {
                    if(w == null){
                        w = wire
                    } else {
                        if (tile.getManhattanDistance(wire.tile) > tile.getManhattanDistance(w.tile)) {
                            w = wire
                        }
                    }
                }

                var startTile = w!!.tile
                npj.forward_pjs.add(NetcrackerPipJunctionConn(name = w.wireName, x = startTile.tileXCoordinate, y = startTile.tileYCoordinate))
            }

            /** Special handling for IMUX and logic outs pip junctions. These go through the pseudo-interconnect between the switchbox
             * and the CLB. We want to circumvent this pseudo-interconnect and find the CLB pin of which the IMUX connects to.
             */
            if(pj.wireName.contains("IMUX")) {
                var nodeToCLB = pj.node.allDownhillNodes[0]
                var wire = nodeToCLB.allWiresInNode[0]

                var endTile = wire.tile
                npj.forward_pjs.add(NetcrackerPipJunctionConn(name = wire.wireName, x = endTile.tileXCoordinate, y = endTile.tileYCoordinate))

            }

            if(pj.wireName.contains("LOGIC_OUTS")) {
                var wireFromCLB = pj.node.allWiresInNode[0].backwardPIPs[0].startWire
                var endTile = wireFromCLB.tile
                npj.backwards_pjs.add(NetcrackerPipJunctionConn(name = wireFromCLB.wireName, x = endTile.tileXCoordinate, y = endTile.tileYCoordinate))

            }

            pjs.add(npj)
        }

        var nutOut = JSONObject()
        nutOut.put("name", name)
        nutOut.put("x", tile.tileXCoordinate)
        nutOut.put("y", tile.tileYCoordinate)
        nutOut.put("pip_junctions", pjs)
        return nutOut
    }
}
