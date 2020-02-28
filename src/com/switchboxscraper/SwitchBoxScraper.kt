package com.switchboxscraper

import com.xilinx.rapidwright.design.Design
import com.xilinx.rapidwright.device.Device
import com.xilinx.rapidwright.router.Router
import com.xilinx.rapidwright.device.PIP
import com.xilinx.rapidwright.device.Wire

import guru.nidi.graphviz.attribute.Color
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.model.Factory.mutGraph
import guru.nidi.graphviz.model.Factory.mutNode
import java.io.File

class SwitchBoxScraper {
    var pipJunctions = mutableSetOf<Wire>()
    var clbPips = mutableListOf<Int>()
    var llPips = mutableListOf<Int>() // Long lines

    var device : Device;

    constructor(deviceName : String) {
        device = Device.getDevice(deviceName)

        scrape()
    }

    fun scrape() {
        // Locate some switchbox tile
        val sbTile = device.getTile("INT_R_X41Y61")
        var sbPips = sbTile.getPIPs()

        // Extract all pip junctions by iterating over all PIPs in the tile and collecting the pip start/stop wires
        // which are defined as the pip junctions
        for(pip in sbPips){
            pipJunctions.add(pip.startWire)
            pipJunctions.add(pip.endWire)
        }

        for(j in pipJunctions){
            println(j)
        }

    }
}

fun main(args: Array<String>) {
    val test = SwitchBoxScraper("xc7a35t")
}