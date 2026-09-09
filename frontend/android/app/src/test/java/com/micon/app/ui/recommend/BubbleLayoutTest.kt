package com.micon.app.ui.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/**
 * 버블 배치 규칙 고정: 어떤 화면 비율·장수에서도 **LP 끼리 겹치지 않고 화면 밖으로 잘리지 않는다**.
 * (기기마다 세로 비율이 달라 이 두 가지가 계속 깨졌고, 잘려 나간 LP 는 위쪽 필터 바와도 겹쳐 보였다)
 */
class BubbleLayoutTest {
    /** 폰 세로 화면에서 나올 수 있는 군집 영역 비율 (세로/가로) */
    private val aspects = listOf(0.9f, 1.0f, 1.15f, 1.3f, 1.45f, 1.6f, 1.8f)

    @Test
    fun `circles never overlap and stay inside the area`() {
        for (aspect in aspects) for (n in 1..WEIGHTS.size) {
            val slots = packBubbles(aspect, WEIGHTS.take(n))
            assertEquals(n, slots.size)
            slots.forEach { (x, y, d) ->
                val r = d / 2
                assertTrue("aspect=$aspect n=$n 잘림: x=$x r=$r", x - r >= -1e-3f && x + r <= 1f + 1e-3f)
                assertTrue("aspect=$aspect n=$n 잘림: y=$y r=$r", y - r >= -1e-3f && y + r <= aspect + 1e-3f)
            }
            for (i in slots.indices) for (j in i + 1 until slots.size) {
                val (xi, yi, di) = slots[i]
                val (xj, yj, dj) = slots[j]
                assertTrue("aspect=$aspect n=$n 겹침: $i-$j", hypot(xj - xi, yj - yi) >= (di + dj) / 2 - 1e-3f)
            }
        }
    }

    /** 여백이 남지 않게 — 원들이 영역의 절반 이상을 덮는다 */
    @Test
    fun `circles fill a good share of the area`() {
        for (aspect in aspects) {
            val covered = packBubbles(aspect, WEIGHTS).sumOf { (_, _, d) -> PI / 4 * d * d }.toFloat() / aspect
            assertTrue("aspect=$aspect 넓이비 $covered 가 너무 작다", covered >= 0.5f)
        }
    }
}

/** 화면에 띄우는 LP 7장의 상대 크기 (RecommendScreens 의 BUBBLE_WEIGHTS 와 같아야 한다) */
private val WEIGHTS = listOf(1f, 0.92f, 0.88f, 0.84f, 0.80f, 0.76f, 0.72f)
