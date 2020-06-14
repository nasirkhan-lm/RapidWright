package com.switchboxscraper

import com.xilinx.rapidwright.device.Device
import com.xilinx.rapidwright.device.Wire
import org.json.JSONObject
import java.io.File

fun calculateChannelWidths(sb: Switchbox) {
    var hz = 0
    var vt = 0

    // Consider non-bidirectional outputs
    for (pj in sb.outPipJunctions) {
        val info = sb.analyseNodeChannelContribution(pj.node)

        hz += info["Hz"]!!
        vt += info["Vt"]!!
    }

    /** Consider bidirectional I/Os. In this case, we find each pair of bidirectional wires, and then only count on of
     * the wires within the pair in our channel sizing - given that a single bidirectional wire in the channel will
     * account for 2 PIP junctions in the switchbox.
     * First, classify bidirectional wires into a map of { length : { direction : [wires]}
     */
    var bidirWires = mutableMapOf<Int, MutableMap<Direction, MutableList<Wire>>>()
    for (bdw in sb.bidirPipJunctions) {
        val len = sb.analyseNodeChannelContribution(bdw.node)["length"]!!
        val dir = getNodeDirection(bdw.node)

        if (!bidirWires.containsKey(len)) {
            bidirWires[len] = mutableMapOf<Direction, MutableList<Wire>>()
        }
        if (!bidirWires[len]!!.containsKey(dir)) {
            bidirWires[len]!![dir] = mutableListOf<Wire>()
        }

        bidirWires[len]!![dir]!!.add(bdw)
    }

    /** Next, count one wire in each pair of wires **/
    for ((len, dirs) in bidirWires) {
        for ((dir, wires) in dirs) {
            if (wires.size % 2 != 0) {
                throw Exception("Error: non-pair of bidirectional wires found. Is the bidirectional wire crossing some IP?")
            }
            for (i in wires.indices) {
                if (i % 2 == 1) {
                    continue
                }
                if (dir == Direction.N || dir == Direction.S) {
                    vt += len
                } else if (dir == Direction.E || dir == Direction.W) {
                    hz += len
                } else {
                    throw Exception("ordinal bidirectional wire, this is impossible.")
                }
            }
        }
    }

    println("${sb.name} Channel sizes:")
    println("Hz:\t{$hz}")
    println("Hz:\t{$vt}")
}

fun dumpNetcrackerToFile(switchboxes : MutableList<Switchbox>) {
    var mapForIC = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

    var nuts = JSONObject()
    for (sb in switchboxes) {
        val nut = sb.dumpToNetcracker()
        nuts.put(sb.name, nut)
    }

    val title = switchboxes.fold(mutableListOf<String>()) { acc, it ->
        acc.add(it.name);
        acc
    }.joinToString(separator = "_")


    File(title + "_nutdata.json").writeText(nuts.toString(4))
}

fun main(args: Array<String>) {
    // Switchbox(es) to investigate
    val deviceName = "xc7a35t"
    val tiles = listOf("INT_L_X16Y125")


    var device: Device = Device.getDevice(deviceName)
    var switchboxes = tiles.fold(mutableListOf<Switchbox>()) { acc, tile -> acc.add(Switchbox(device.getTile(tile))); acc }

    dumpNetcrackerToFile(switchboxes)

    switchboxes.map { calculateChannelWidths(it) }
}