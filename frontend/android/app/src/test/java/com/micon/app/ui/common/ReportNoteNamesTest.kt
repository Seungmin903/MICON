package com.micon.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportNoteNamesTest {
    @Test fun reportNotationUsesTheRequestedOctaveAndKoreanSyllable() {
        assertEquals("3G(솔)", ReportNoteNames.note(55))
        assertEquals("3F(파) ~ 4A(라)", ReportNoteNames.range(53, 69))
        assertEquals("4C#(도#)", ReportNoteNames.note(61))
        assertEquals("3B(시)", ReportNoteNames.note(59))
        assertEquals("4C(도)", ReportNoteNames.note(60))
        assertEquals("2C(도)", ReportNoteNames.note(36))
        assertEquals("6C(도)", ReportNoteNames.note(84))
    }

    @Test fun savedScientificLabelsUseTheSameNotationWithoutChangingOtherLabels() {
        assertEquals("3G(솔)", ReportNoteNames.fromScientific("G3"))
        assertEquals("4C#(도#)", ReportNoteNames.fromScientific("Db4"))
        assertEquals("4C(도)", ReportNoteNames.fromScientific("B#3"))
        assertEquals("미측정", ReportNoteNames.fromScientific("미측정"))
    }
}
