package com.micon.app.ui.common

import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class SharpNoteTextTest {
    @Test fun normalizesSharpAndRendersOnlyAccidentalsAtThreeQuarterSize() {
        val text = sharpNoteText("4F♯(파#)", 20.sp)
        assertEquals("4F#(파#)", text.text)
        assertEquals(2, text.spanStyles.size)
        assertEquals(15.sp, text.spanStyles[0].item.fontSize)
        assertEquals(15.sp, text.spanStyles[1].item.fontSize)
    }
}
