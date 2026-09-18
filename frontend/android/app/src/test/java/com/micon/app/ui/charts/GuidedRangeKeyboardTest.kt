package com.micon.app.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedRangeKeyboardTest {
    @Test fun targetOctaveIsSurroundedByTwoMoreOctavesInsideTheSupportedRange() {
        assertEquals(36..72, guidedKeyboardRange(36, GuidedKeyboardMode.THREE_OCTAVES))
        assertEquals(36..72, guidedKeyboardRange(59, GuidedKeyboardMode.THREE_OCTAVES))
        assertEquals(48..84, guidedKeyboardRange(60, GuidedKeyboardMode.THREE_OCTAVES))
        assertEquals(48..84, guidedKeyboardRange(84, GuidedKeyboardMode.THREE_OCTAVES))
    }

    @Test fun octaveWindowStaysInsideTheSupportedKeyboard() {
        assertEquals(36..72, guidedKeyboardRange(20, GuidedKeyboardMode.THREE_OCTAVES))
        assertEquals(48..84, guidedKeyboardRange(100, GuidedKeyboardMode.THREE_OCTAVES))
    }

    @Test fun fullKeyboardNeverFollowsTheTargetAndDoesNotLabelTheTerminalC() {
        listOf(36, 60, 84).forEach {
            assertEquals(36..84, guidedKeyboardRange(it, GuidedKeyboardMode.FULL_RANGE))
        }
        assertEquals(listOf(2, 3, 4, 5), guidedKeyboardOctaves(36..84))
        assertEquals(listOf(2, 3, 4), guidedKeyboardOctaves(36..72))
        assertEquals(listOf(3, 4, 5), guidedKeyboardOctaves(48..84))
    }

    @Test fun preparationComfortableAndProfileChoiceUseTheFullKeyboard() {
        listOf("noise", "comfortable", "profile_ready").forEach {
            assertEquals(GuidedKeyboardMode.FULL_RANGE, measurementKeyboardMode(it))
        }
        listOf("anchor", "central", "down", "up", "reconfirm", "extension").forEach {
            assertEquals(GuidedKeyboardMode.THREE_OCTAVES, measurementKeyboardMode(it))
        }
    }
}
