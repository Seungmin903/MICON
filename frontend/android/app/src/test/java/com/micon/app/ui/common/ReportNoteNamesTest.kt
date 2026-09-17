package com.micon.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportNoteNamesTest {
    @Test fun reportNotationUsesTheRequestedOctaveAndKoreanSyllable() {
        assertEquals("4G(솔)", ReportNoteNames.note(55))
        assertEquals("4F(파) ~ 5A(라)", ReportNoteNames.range(53, 69))
        assertEquals("5C♯(도♯)", ReportNoteNames.note(61))
        assertEquals("4B(시)", ReportNoteNames.note(59))
        assertEquals("5C(도)", ReportNoteNames.note(60))
        assertEquals("3C(도)", ReportNoteNames.note(36))
        assertEquals("7C(도)", ReportNoteNames.note(84))
    }

    @Test fun savedScientificLabelsUseTheSameNotationWithoutChangingOtherLabels() {
        assertEquals("4G(솔)", ReportNoteNames.fromScientific("G3"))
        assertEquals("5C♯(도♯)", ReportNoteNames.fromScientific("Db4"))
        assertEquals("5C(도)", ReportNoteNames.fromScientific("B#3"))
        assertEquals("미측정", ReportNoteNames.fromScientific("미측정"))
    }
}
