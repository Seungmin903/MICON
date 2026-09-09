package com.micon.app.ui.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

/**
 * 버블 배치 규칙 고정: 어떤 화면 비율·장수에서도 **LP 끼리 겹치지 않고**, 화면 밖으로 나가더라도 정해진 [BLEED] 까지만 나간다.
 * (기기마다 세로 비율이 달라 이 두 가지가 예전에 계속 깨졌다)
 */
class BubbleLayoutTest {
    /** 폰 세로 화면에서 나올 수 있는 군집 영역 비율 (세로/가로) */
    private val aspects = listOf(0.9f, 1.0f, 1.15f, 1.3f, 1.45f, 1.6f, 1.8f)

    @Test
    fun `circles never overlap and stay within the allowed bleed`() {
        for (aspect in aspects) for (n in 1..WEIGHTS.size) {
            val slots = packBubbles(aspect, WEIGHTS.take(n), bleed = BLEED)
            assertEquals(n, slots.size)
            slots.forEach { (x, y, d) ->
                val r = d / 2
                assertTrue("aspect=$aspect n=$n 너무 많이 잘림: x=$x r=$r", x - r >= -BLEED - 1e-3f && x + r <= 1f + BLEED + 1e-3f)
                assertTrue("aspect=$aspect n=$n 너무 많이 잘림: y=$y r=$r", y - r >= -BLEED - 1e-3f && y + r <= aspect + BLEED + 1e-3f)
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
            val covered = packBubbles(aspect, WEIGHTS, bleed = BLEED).sumOf { (_, _, d) -> PI / 4 * d * d }.toFloat() / aspect
            assertTrue("aspect=$aspect 넓이비 $covered 가 너무 작다", covered >= 0.5f)
        }
    }

    /**
     * 장수를 5 → 8 로 늘리면 넓이 총합이 같으므로 LP 지름이 약 80 % 가 된다(간격은 그대로).
     * 화면에 8장을 띄우면서 크기를 80 % 로 줄이기로 한 결정이 이 관계에 기대고 있다.
     */
    @Test
    fun `more discs means proportionally smaller discs at the same spacing`() {
        for (aspect in aspects) {
            val five = packBubbles(aspect, listOf(1f, 0.86f, 0.86f, 0.76f, 0.76f), bleed = BLEED)[0].third
            val eight = packBubbles(aspect, WEIGHTS, bleed = BLEED)[0].third
            val ratio = eight / five
            assertTrue("aspect=$aspect 지름 비 $ratio 가 80 % 근처가 아니다", ratio in 0.75f..0.87f)
        }
    }
}

private val WEIGHTS = listOf(1f, 0.92f, 0.88f, 0.84f, 0.80f, 0.76f, 0.72f, 0.66f)
private const val BLEED = 0.10f
