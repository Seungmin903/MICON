package com.micon.backend.domain.practice.service;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.practice.repository.PracticeRepository;
import com.micon.backend.domain.practice.repository.PracticeResultRepository;
import com.micon.backend.domain.practice.dto.response.PracticeResponse;
import com.micon.backend.domain.practice.dto.request.PracticeResultRequest;
import com.micon.backend.domain.practice.dto.request.PracticeResultRequest.SectionEvaluation;
import com.micon.backend.domain.practice.dto.response.PracticeResultResponse;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 기기에서 계산한 연습 평가를 저장하고 연습 리포트 및 취약 구간 재연습 정보를 구성한다. */
@Service
public class PracticeResultService {

	private static final int SUMMARY_COLUMN_LIMIT = 1000;

	private final PracticeRepository practiceRepository;
	private final PracticeResultRepository resultRepository;
	private final ObjectMapper objectMapper;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeResultService 객체를 생성한다.
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
	// 기능: save 업무 규칙을 검증하고 데이터를 생성하거나 저장한다.
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
	// 기능: report 비즈니스 로직을 수행한다.
	public PracticeResultResponse.Report report(Long userId, Long practiceId) {
		PracticeResponse.Detail practice = ownedPractice(userId, practiceId);
		PracticeResultRepository.ResultRow result = resultRepository.findLatest(practiceId)
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
			practice.practiceId(), practice.songId(), practice.songTitle(), practice.initialKey(),
			practice.selectedKey(), practice.keyChangeCount(), practice.keyChanged(),
			practice.practiceScope(), practice.sectionId(), practice.durationMs(), result.status(),
			result.overallScore(), result.pitchScore(), result.sustainScore(), result.rhythmScore(),
			payload.summaryText(), payload.sections(), weakSection, result.analyzedAt()
		);
	}

	@Transactional(readOnly = true)
	// 기능: analysis 비즈니스 로직을 수행한다.
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
	// 기능: retry 업무 규칙을 검증하고 데이터를 생성하거나 저장한다.
	public PracticeResultResponse.Analysis retry(Long userId, Long practiceId) {
		ownedPractice(userId, practiceId);
		PracticeResultRepository.ResultRow result = resultRepository.findLatest(practiceId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_RESULT_NOT_FOUND));
		if (resultRepository.retry(result.resultId()) == 0) {
			throw new BusinessException(ErrorCode.INVALID_PRACTICE_STATE);
		}
		return new PracticeResultResponse.Analysis(practiceId, "ANALYZING", false);
	}

	// 기능: ownedPractice 비즈니스 로직을 수행한다.
	private PracticeResponse.Detail ownedPractice(Long userId, Long practiceId) {
		return practiceRepository.findOwned(practiceId, userId)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_NOT_FOUND));
	}

	// 기능: validateCompletedScores 처리에 필요한 업무 조건을 검증한다.
	private void validateCompletedScores(PracticeResultRequest.Save request) {
		if (request.status() == PracticeResultRequest.ResultStatus.COMPLETED
			&& (request.overallScore() == null || request.pitchScore() == null
			|| request.rhythmScore() == null)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "완료된 연습 결과에는 종합·음정·리듬 점수가 필요합니다.");
		}
	}

	// 기능: writeSummary 비즈니스 로직을 수행한다.
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

	// 기능: readSummary 비즈니스 로직을 수행한다.
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

	// 기능: nextIdentifier 저장에 사용할 새 식별자를 생성한다.
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
