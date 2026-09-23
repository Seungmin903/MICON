package com.micon.backend.domain.practice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 같은 곡을 반복 연습한 완료 점수의 시간순 변화다. */
public record PracticeScoreTrendResponse(
        String songId, int totalCount, BigDecimal firstScore, BigDecimal latestScore,
        BigDecimal bestScore, BigDecimal scoreDelta, List<Point> points) {
    public record Point(Long practiceId, LocalDateTime playedAt, BigDecimal overallScore,
                        BigDecimal pitchScore, BigDecimal rhythmScore, int selectedKey,
                        String practiceScope, Integer durationMs) { }
}
