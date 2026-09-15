package com.micon.app.range

import java.io.File

fun interface RangeAnalysisGateway {
    suspend fun analyze(wav: File, submission: RangeSubmission): RangeReport
}
