package com.micon.backend.domain.practice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.domain.practice.dto.request.PracticeRequest;
import com.micon.backend.domain.practice.dto.request.PracticeResultRequest;
import com.micon.backend.domain.practice.dto.response.PracticeHistoryResponse;
import com.micon.backend.domain.practice.dto.response.PracticeResponse;
import com.micon.backend.domain.practice.dto.response.PracticeResultResponse;
import com.micon.backend.domain.practice.service.PracticeResultService;
import com.micon.backend.domain.practice.service.PracticeService;
import com.micon.backend.domain.practice.service.PracticeHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 연습 세션 실행과 평가 저장, 기록·리포트 조회 및 삭제 요청을 받는 HTTP API 진입점이다. */
@Validated
@RestController
@RequestMapping("/practices")
@Tag(name = "연습", description = "연습 세션 실행과 연습 리포트 관리 API")
public class PracticeController {

	private final PracticeService service;
	private final PracticeResultService resultService;
	private final PracticeHistoryService historyService;

	// 기능: 필요한 의존성과 초기값을 받아 PracticeController 객체를 생성한다.
	public PracticeController(
		PracticeService service,
		PracticeResultService resultService,
		PracticeHistoryService historyService
	) {
		this.service = service;
		this.resultService = resultService;
		this.historyService = historyService;
	}

	@GetMapping("/report")
	// 기능: getHistory 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ApiResponse<PracticeHistoryResponse.Page> getHistory(
		Principal principal,
		@Parameter(description = "조회 날짜(yyyy-MM-dd)")
		@RequestParam(name = "date", required = false) LocalDate date,
		@Parameter(description = "페이지 번호(0부터 시작)")
		@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
		@Parameter(description = "페이지 크기(1~100)")
		@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(historyService.getHistory(userId(principal), date, page, size));
	}

	@Operation(summary = "연습 세션 생성", description = "선택한 곡, 키, 재생 속도와 연습 범위로 연습 세션을 생성합니다.")
	@PostMapping("/session")
	// 기능: create 생성·처리 요청을 검증하고 서비스에 위임한다.
	public ApiResponse<PracticeResponse.Detail> create(
		Principal principal,
		@Valid @RequestBody PracticeRequest.Create request
	) {
		return ApiResponse.success(service.create(userId(principal), request));
	}

//	@Operation(summary = "연습 세션 조회", description = "본인의 연습 세션 기본 정보를 조회합니다.")
//	@GetMapping("/{practiceId}")
//	// 기능: get 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
//	public ApiResponse<PracticeResponse.Detail> get(
//		Principal principal,
//		@PathVariable("practiceId") @Min(1) Long practiceId
//	) {
//		return ApiResponse.success(service.get(userId(principal), practiceId));
//	}

	@Operation(
		summary = "연습 리포트 개별 삭제",
		description = "본인의 연습 기록과 해당 기록에 속한 점수·구간 리포트를 삭제합니다. 녹음 및 음성 분석 데이터는 삭제하지 않습니다."
	)
	@DeleteMapping("/report/{practiceId}")
	// 기능: delete 삭제·취소 요청을 검증하고 서비스에 위임한다.
	public ApiResponse<Void> delete(
		Principal principal,
		@PathVariable @Min(1) Long practiceId
	) {
		service.delete(userId(principal), practiceId);
		return ApiResponse.success(null);
	}

	@Operation(summary = "연습 완료", description = "연습 시간과 선택적 녹음·분석 식별자를 저장하고 연습을 완료 처리합니다.")
	@PostMapping("/{practiceId}/complete")
	// 기능: complete 생성·처리 요청을 검증하고 서비스에 위임한다.
	public ApiResponse<PracticeResponse.Detail> complete(
		Principal principal,
		@PathVariable("practiceId") @Min(1) Long practiceId,
		@Valid @RequestBody PracticeRequest.Complete request
	) {
		return ApiResponse.success(service.complete(userId(principal), practiceId, request));
	}

	@Operation(summary = "연습 키 변경", description = "진행 중인 연습 세션의 현재 키와 실제 키 변경 횟수를 저장합니다.")
	@PatchMapping("/{practiceId}/key")
	public ApiResponse<PracticeResponse.Detail> updateKey(
		Principal principal,
		@PathVariable("practiceId") @Min(1) Long practiceId,
		@Valid @RequestBody PracticeRequest.KeyUpdate request
	) {
		return ApiResponse.success(service.updateKey(userId(principal), practiceId, request));
	}

	@Operation(summary = "연습 취소", description = "진행 중인 본인의 연습 세션을 취소합니다.")
	@PostMapping("/{practiceId}/cancel")
	// 기능: cancel 삭제·취소 요청을 검증하고 서비스에 위임한다.
	public ApiResponse<PracticeResponse.Detail> cancel(
		Principal principal,
		@PathVariable("practiceId") @Min(1) Long practiceId
	) {
		return ApiResponse.success(service.cancel(userId(principal), practiceId));
	}

//	@Operation(summary = "연습 상태 변경", description = "연습 상태를 녹음 중, 진행 중 또는 일시정지로 변경합니다.")
//	@PatchMapping("/{practiceId}/status")
//	// 기능: updateStatus 변경 요청을 검증하고 서비스에 위임한다.
//	public ApiResponse<PracticeResponse.Detail> updateStatus(
//		Principal principal,
//		@PathVariable("practiceId") @Min(1) Long practiceId,
//		@Valid @RequestBody PracticeRequest.StatusUpdate request
//	) {
//		return ApiResponse.success(service.updateStatus(userId(principal), practiceId, request));
//	}

	@Operation(summary = "연습 리포트 저장", description = "기기 또는 분석 결과로 계산한 점수와 구간별 평가를 저장합니다.")
	@PutMapping("/report/{practiceId}")
	// 기능: savePerformance 생성·처리 요청을 검증하고 서비스에 위임한다.
	public ApiResponse<PracticeResultResponse.Report> savePerformance(
		Principal principal,
		@PathVariable("practiceId") @Min(1) Long practiceId,
		@Valid @RequestBody PracticeResultRequest.Save request
	) {
		return ApiResponse.success(resultService.save(userId(principal), practiceId, request));
	}

//	@Operation(summary = "연습 분석 상태 조회", description = "연습 분석의 진행 또는 실패 상태를 조회합니다.")
//	@GetMapping("/{practiceId}/analysis")
//	// 기능: getAnalysis 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
//	public ApiResponse<PracticeResultResponse.Analysis> getAnalysis(
//		Principal principal,
//		@PathVariable("practiceId") @Min(1) Long practiceId
//	) {
//		return ApiResponse.success(resultService.analysis(userId(principal), practiceId));
//	}

	@Operation(summary = "연습 리포트 상세 조회", description = "저장된 종합·음정·음 유지·리듬 점수와 취약 구간을 조회합니다.")
	@GetMapping("/report/{practiceId}")
	// 기능: getReport 조회 요청을 검증하고 서비스 결과를 HTTP 응답으로 반환한다.
	public ApiResponse<PracticeResultResponse.Report> getReport(
		Principal principal,
		@PathVariable("practiceId") @Min(1) Long practiceId
	) {
		return ApiResponse.success(resultService.report(userId(principal), practiceId));
	}

//	@Operation(summary = "연습 분석 재시도", description = "실패한 본인의 연습 분석 작업을 다시 시작합니다.")
//	@PostMapping("/{practiceId}/analysis/retry")
//	// 기능: retryAnalysis 생성·처리 요청을 검증하고 서비스에 위임한다.
//	public ApiResponse<PracticeResultResponse.Analysis> retryAnalysis(
//		Principal principal,
//		@PathVariable("practiceId") @Min(1) Long practiceId
//	) {
//		return ApiResponse.success(resultService.retry(userId(principal), practiceId));
//	}

	// 기능: 인증 토큰에서 현재 사용자 ID를 추출하고 유효성을 확인한다.
	private Long userId(Principal principal) {
		return Long.valueOf(principal.getName());
	}
}
