package com.micon.backend.practice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.practice.api.PracticeResponse;
import com.micon.backend.practice.api.PracticeResultRequest;
import com.micon.backend.practice.api.PracticeResultRequest.SectionEvaluation;
import com.micon.backend.practice.api.PracticeResultResponse;
import com.micon.backend.practice.repository.PracticeRepository;
import com.micon.backend.practice.repository.PracticeResultRepository;
import com.micon.backend.practice.repository.PracticeResultRepository.ResultRow;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class PracticeResultService {

	private static final int SUMMARY_COLUMN_LIMIT = 1000;

	private final PracticeRepository practiceRepository;
	private final PracticeResultRepository resultRepository;
	private final ObjectMapper objectMapper;

	public PracticeResultService(
		PracticeRepository practiceRepository,
		PracticeResultRepository resultRepository,
		ObjectMapper objectMapper
	) {
		this.practiceRepository = practiceRepository;
		this.resultRepository = resultRepository;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public PracticeResultResponse.Report save(
		Long userId,
		Long practiceId,
		PracticeResultRequest.Save request
	) {
		PracticeResponse.Detail practice = ownedPractice(userId, practiceId);
		if (!"COMPLETED".equals(practice.status())) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		List<SectionEvaluation> sections = request.sections() == null ? List.of() : List.copyOf(request.sections());
		for (SectionEvaluation section : sections) {
			if (!practiceRepository.sectionBelongsToSong(section.sectionId(), practice.songId())) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, "평가 구간이 해당 곡에 속하지 않습니다.");
			}
		}
		validateCompletedScores(request);
		String summaryJson = writeSummary(new SummaryPayload(request.summaryText(), sections));
		resultRepository.save(
			nextIdentifier(), practiceId, request.status().name(), request.overallScore(), request.pitchScore(),
			request.sustainScore(), request.rhythmScore(), summaryJson
		);
		return report(userId, practiceId);
	}

	@Transactional(readOnly = true)
	public PracticeResultResponse.Report report(Long userId, Long practiceId) {
		PracticeResponse.Detail practice = ownedPractice(userId, practiceId);
		ResultRow result = resultRepository.findLatest(practiceId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_RESULT_NOT_FOUND));
		SummaryPayload payload = readSummary(result.summaryJson());
		PracticeResultResponse.RetrySection weakSection = payload.sections().stream()
			.filter(section -> Boolean.TRUE.equals(section.measured()) && section.score() != null)
			.min(Comparator.comparing(SectionEvaluation::score))
			.map(section -> new PracticeResultResponse.RetrySection(
				section.sectionId(), practice.selectedKey(), section.score()
			))
			.orElse(null);
		return new PracticeResultResponse.Report(
			practice.practiceId(), practice.songId(), practice.songTitle(), practice.selectedKey(),
			practice.practiceScope(), practice.sectionId(), practice.durationMs(), result.status(),
			result.overallScore(), result.pitchScore(), result.sustainScore(), result.rhythmScore(),
			payload.summaryText(), payload.sections(), weakSection, result.analyzedAt()
		);
	}

	@Transactional(readOnly = true)
	public PracticeResultResponse.Analysis analysis(Long userId, Long practiceId) {
		PracticeResponse.Detail practice = ownedPractice(userId, practiceId);
		if (!"COMPLETED".equals(practice.status())) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return resultRepository.findLatest(practiceId)
			.map(result -> new PracticeResultResponse.Analysis(
				practiceId, result.status(), "FAILED".equals(result.status())
			))
			.orElseGet(() -> new PracticeResultResponse.Analysis(practiceId, "ANALYZING", false));
	}

	@Transactional
	public PracticeResultResponse.Analysis retry(Long userId, Long practiceId) {
		ownedPractice(userId, practiceId);
		ResultRow result = resultRepository.findLatest(practiceId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_RESULT_NOT_FOUND));
		if (resultRepository.retry(result.resultId()) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return new PracticeResultResponse.Analysis(practiceId, "ANALYZING", false);
	}

	private PracticeResponse.Detail ownedPractice(Long userId, Long practiceId) {
		return practiceRepository.findOwned(practiceId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_NOT_FOUND));
	}

	private void validateCompletedScores(PracticeResultRequest.Save request) {
		if (request.status() == PracticeResultRequest.ResultStatus.COMPLETED
			&& (request.overallScore() == null || request.pitchScore() == null
			|| request.rhythmScore() == null)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "완료된 연습 결과에는 종합·음정·리듬 점수가 필요합니다.");
		}
	}

	private String writeSummary(SummaryPayload payload) {
		try {
			String json = objectMapper.writeValueAsString(payload);
			if (json.length() > SUMMARY_COLUMN_LIMIT) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, "연습 결과 요약이 저장 가능 길이를 초과했습니다.");
			}
			return json;
		} catch (BusinessException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "연습 결과 요약 형식이 올바르지 않습니다.");
		}
	}

	private SummaryPayload readSummary(String summaryJson) {
		if (summaryJson == null || summaryJson.isBlank()) {
			return new SummaryPayload(null, List.of());
		}
		try {
			SummaryPayload payload = objectMapper.readValue(summaryJson, SummaryPayload.class);
			return new SummaryPayload(
				payload.summaryText(), payload.sections() == null ? List.of() : payload.sections()
			);
		} catch (Exception exception) {
			return new SummaryPayload(summaryJson, List.of());
		}
	}

	private Long nextIdentifier() {
		Long identifier;
		do {
			identifier = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
		} while (resultRepository.identifierExists(identifier));
		return identifier;
	}

	public record SummaryPayload(String summaryText, List<SectionEvaluation> sections) {
	}
}
