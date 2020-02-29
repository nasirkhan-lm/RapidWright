package com.switchboxscraper

import com.xilinx.rapidwright.device.Device
import com.xilinx.rapidwright.device.Wire

import guru.nidi.graphviz.attribute.Color
import guru.nidi.graphviz.attribute.Shape
import guru.nidi.graphviz.engine.Format
import guru.nidi.graphviz.engine.Graphviz
import guru.nidi.graphviz.model.Factory.mutGraph
import guru.nidi.graphviz.model.Factory.mutNode
import guru.nidi.graphviz.model.MutableNode


import java.io.File
import java.util.*

/**
 *
 */
enum class PJClass {
    CLK,
    ELEC,
    CONN
}

enum class PJType {
    SOURCE,
    SINK,
    BUF,
    INTERNAL
}

class SwitchBoxScraper {
    var pipJunctions = mutableSetOf<Wire>()
    var clbPips = mutableListOf<Int>()
    var llPips = mutableListOf<Int>() // Long lines
    var name : String = ""

    var device : Device;

    constructor(deviceName : String) {
        device = Device.getDevice(deviceName)
        scrape()
    }

    fun scrape() {
        // Locate some switchbox tile
        val sbTile = device.getTile("INT_R_X41Y61")
        this.name = sbTile.name
        println("Scraping Switchox: ${name}")


        // Extract all pip junctions by iterating over all PIPs in the tile and collecting the pip start/stop wires
        // which are defined as the pip junctions
        for(pip in sbTile.getPIPs()){
            pipJunctions.add(pip.startWire)
            pipJunctions.add(pip.endWire)
        }

        var dotFiles = mutableListOf<String>()
        val graphTypes = listOf(EnumSet.of(PJClass.ELEC), EnumSet.of(PJClass.CONN), EnumSet.of(PJClass.CLK), EnumSet.allOf(PJClass::class.java))
        for(type in graphTypes) {
            dotFiles.add(createDotGraph(type))
        }

        val wroteFiles = dotFiles + dotsToSvg(dotFiles)
        println("Wrote files:")
        wroteFiles.map { v -> println("\t${v}") }
    }


    fun pjClass(wire : Wire) : PJClass {
        val wn = wire.wireName
        when {
            wn.contains("CLK") -> return PJClass.CLK
            wn.contains("VCC") || wn.contains("GND") -> return PJClass.ELEC
            else -> return PJClass.CONN
        }
    }

    fun pjOfClass(t : PJClass)  =  pipJunctions.filter { pj -> pjClass(pj) == t }

    fun pjType(pj : Wire) : PJType {
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

    fun createPJGraphNode(wire : Wire) : MutableNode {
        var node = mutNode(wire.wireName)
        // Color classification by PIP junction class
        when {
            pjClass(wire) == PJClass.ELEC -> node.add(Color.RED).add("fillcolor", "red2").add("style", "filled")
            pjClass(wire) == PJClass.CLK -> node.add(Color.YELLOW).add("fillcolor", "yellow2").add("style", "filled")
            else -> node.add(Color.BLACK).add("fillcolor", "gray").add("style", "filled")
        }

        // Shape classification by PIP junction type
        when {
            pjType(wire) == PJType.SOURCE -> node.add(Shape.RECTANGLE)
            pjType(wire) == PJType.SINK -> node.add(Shape.DIAMOND)
        }

        return node
    }

    fun dotsToSvg(files : List<String>) : List<String> {
        for(f in files) {
            Runtime.getRuntime().exec("dot -Ksfdp -Tsvg -Goverlap=scale -O ${f}")
        }
        return files.map {file -> file + ".svg"}
    }

    fun createDotGraph(includes : EnumSet<PJClass>) : String {
        val includeString = includes.fold(""){str, v -> str + v}
        println("\tCreating graph for PIPs of type(s): ${includeString}")
        var pjNodes = mutableMapOf<Wire, MutableNode>()

        for(pj in pipJunctions) {
            val wireName = pj.wireName
            if(pjClass(pj) == PJClass.CLK) {
                if(!includes.contains(PJClass.CLK)) {
                    // Clock wires excluded
                    continue
                }
            } else if (pjClass(pj) == PJClass.ELEC) {
                if (!includes.contains(PJClass.ELEC)) {
                    // Electrical wires excluded
                    continue
                }
            } else if (!includes.contains(PJClass.CONN)) {
                // Normal routing wires excluded
                continue
            }
            pjNodes[pj] = createPJGraphNode(pj)
        }

        for(pj in pipJunctions) {
            for(toWire in pj.forwardPIPs) {
                var toPJ = toWire.endWire
                if(pjNodes.containsKey(toPJ)) {
                    pjNodes[pj]?.addLink(pjNodes[toPJ])
                }
            }
        }

        // Add nodes to graph
        val graph = mutGraph(name).setDirected(true)
        for(node in pjNodes) {
            graph.add(node.value)
        }

        val fileName =  "graphs/${name}_${includeString}.dot"
        return Graphviz.fromGraph(graph).render(Format.DOT).toFile(File(fileName)).absolutePath
    }
}

fun main(args: Array<String>) {
    val test = SwitchBoxScraper("xc7a35t")
}