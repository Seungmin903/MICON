package com.micon.app.data.onboarding

import com.micon.app.range.PendingRange
import com.micon.app.range.RangeReport
import com.micon.app.range.RangeRepository

/** The onboarding flow owns analysis; leaving the measurement screen must not cancel it. */
interface OnboardingRangeAnalysis {
    fun pending(): PendingRange?
    suspend fun submit(): RangeReport
}

class RepositoryOnboardingRangeAnalysis(private val repository: RangeRepository) : OnboardingRangeAnalysis {
    override fun pending() = repository.pending()
    override suspend fun submit() = repository.submit { }
}
