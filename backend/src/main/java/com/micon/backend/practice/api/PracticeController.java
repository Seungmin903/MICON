package com.micon.backend.practice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.practice.service.PracticeHistoryService;
import com.micon.backend.practice.service.PracticeResultService;
import com.micon.backend.practice.service.PracticeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.security.Principal;
import java.time.LocalDate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/practices")
public class PracticeController {

	private final PracticeService service;
	private final PracticeResultService resultService;
	private final PracticeHistoryService historyService;

	public PracticeController(
		PracticeService service,
		PracticeResultService resultService,
		PracticeHistoryService historyService
	) {
		this.service = service;
		this.resultService = resultService;
		this.historyService = historyService;
	}

	@GetMapping
	public ApiResponse<PracticeHistoryResponse.Page> getHistory(
		Principal principal,
		@RequestParam(required = false) LocalDate date,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
	) {
		return ApiResponse.success(historyService.getHistory(userId(principal), date, page, size));
	}

	@PostMapping
	public ApiResponse<PracticeResponse.Detail> create(
		Principal principal,
		@Valid @RequestBody PracticeRequest.Create request
	) {
		return ApiResponse.success(service.create(userId(principal), request));
	}

	@GetMapping("/{practiceId}")
	public ApiResponse<PracticeResponse.Detail> get(
		Principal principal,
		@PathVariable @Min(1) Long practiceId
	) {
		return ApiResponse.success(service.get(userId(principal), practiceId));
	}

	@PostMapping("/{practiceId}/complete")
	public ApiResponse<PracticeResponse.Detail> complete(
		Principal principal,
		@PathVariable @Min(1) Long practiceId,
		@Valid @RequestBody PracticeRequest.Complete request
	) {
		return ApiResponse.success(service.complete(userId(principal), practiceId, request));
	}

	@PostMapping("/{practiceId}/cancel")
	public ApiResponse<PracticeResponse.Detail> cancel(
		Principal principal,
		@PathVariable @Min(1) Long practiceId
	) {
		return ApiResponse.success(service.cancel(userId(principal), practiceId));
	}

	@PutMapping("/{practiceId}/performance")
	public ApiResponse<PracticeResultResponse.Report> savePerformance(
		Principal principal,
		@PathVariable @Min(1) Long practiceId,
		@Valid @RequestBody PracticeResultRequest.Save request
	) {
		return ApiResponse.success(resultService.save(userId(principal), practiceId, request));
	}

	@GetMapping("/{practiceId}/analysis")
	public ApiResponse<PracticeResultResponse.Analysis> getAnalysis(
		Principal principal,
		@PathVariable @Min(1) Long practiceId
	) {
		return ApiResponse.success(resultService.analysis(userId(principal), practiceId));
	}

	@GetMapping("/{practiceId}/report")
	public ApiResponse<PracticeResultResponse.Report> getReport(
		Principal principal,
		@PathVariable @Min(1) Long practiceId
	) {
		return ApiResponse.success(resultService.report(userId(principal), practiceId));
	}

	@PostMapping("/{practiceId}/analysis/retry")
	public ApiResponse<PracticeResultResponse.Analysis> retryAnalysis(
		Principal principal,
		@PathVariable @Min(1) Long practiceId
	) {
		return ApiResponse.success(resultService.retry(userId(principal), practiceId));
	}

	private Long userId(Principal principal) {
		return Long.valueOf(principal.getName());
	}
}
