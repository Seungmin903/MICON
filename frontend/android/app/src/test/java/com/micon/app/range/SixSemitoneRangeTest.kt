package com.micon.app.range

import org.junit.Assert.*
import org.junit.Test

class SixSemitoneRangeTest {
    @Test fun sixSemitoneCoarseProbesAndAllMidpointOutcomesConverge() {
        for(direction in listOf(-1,1)) {
            val coarse=RangeSearch(60,direction,6,adaptive=true)
            repeat(4) { i ->
                assertEquals(60+direction*6*(i+1),coarse.next())
                coarse.record(coarse.next()!!,true)
            }
            assertNull(coarse.next());assertEquals("model_limit",coarse.boundary)
            for(mask in 0..7) {
                val s=RangeSearch(60,direction,6,adaptive=true)
                s.record(s.next()!!,false)
                val seen=mutableSetOf(60,60+direction*6)
                var i=0
                while(s.next()!=null) {
                    assertTrue(i<3)
                    val pair=s.boundaryPair!!
                    val next=s.next()!!
                    assertEquals((pair[0]+pair[1])/2,next)
                    assertTrue(seen.add(next))
                    s.record(next,mask and (1 shl i)!=0);i++
                }
                assertEquals("boundary_refined",s.boundary)
                assertEquals(direction,s.boundaryPair!![1]-s.boundaryPair!![0])
                assertEquals(60+direction*6,s.frontier)
            }
        }
        assertNull(RangeSearch(80,1,6,adaptive=true).next())
        assertNull(RangeSearch(40,-1,6,adaptive=true).next())
    }
    @Test fun continuousOverviewUsesVerifiedExtremaAndRestoresTwoSemitoneInset() {
        val metrics=RangeMetrics(0.0,0.0,1.0,1.0,1.0)
        val report=RangeReport("guided-range-v7",listOf(
            RangeNote(48,"not_passed",metrics),RangeNote(52,"passed",metrics),
            RangeNote(58,"unmeasured"),RangeNote(64,"passed",metrics),
            RangeNote(66,"needs_review",metrics),RangeNote(68,"not_passed",metrics)),
            52,64,listOf(listOf(52,52),listOf(64,64)),"completed","model_limit","model_limit","available")
        assertEquals(RangeMap(listOf(48,68),listOf(52,64),listOf(54,62)),report.continuousRangeMap())
        assertEquals(listOf(listOf(52,52),listOf(64,64)),report.stableIntervals)
        assertEquals("unmeasured",report.notes[2].status)
        val stored=RangeMap(listOf(45,70),listOf(52,65),listOf(54,63))
        assertEquals(stored,report.copy(protocolVersion="guided-range-v4",rangeMap=stored).continuousRangeMap())
        val narrow=report.copy(notes=listOf(RangeNote(60,"passed",metrics)))
        assertNull(narrow.continuousRangeMap().recommended)
    }
}
