package com.micon.backend.domain.health.dto;

/** 외부에 내부 구성요소 정보를 노출하지 않는 최소 헬스체크 응답이다. */
public record HealthResponse(String status) {
}
