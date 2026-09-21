package com.micon.app.ui.common

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private const val SHARP_SCALE = 0.75f

fun String.rangeSharpNotation(): String = replace('♯', '#')

/** Keeps the accessible/plain value as F# while rendering only the accidental smaller. */
fun sharpNoteText(text: String, fontSize: TextUnit): AnnotatedString = buildAnnotatedString {
    text.rangeSharpNotation().forEach { character ->
        if (character == '#') {
            withStyle(SpanStyle(fontSize = (fontSize.value * SHARP_SCALE).sp)) { append(character) }
        } else append(character)
    }
}
