package com.micon.backend.domain.embedding.api;

import com.micon.backend.domain.embedding.service.EmbeddingCatalogService;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.domain.voice.service.AiServiceTokenValidator;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class EmbeddingCatalogController {
    private final AiServiceTokenValidator tokens;
    private final EmbeddingCatalogService catalog;

    public EmbeddingCatalogController(AiServiceTokenValidator tokens, EmbeddingCatalogService catalog) {
        this.tokens = tokens;
        this.catalog = catalog;
    }

    @GetMapping("/internal/embedding-catalog/active")
    public ResponseEntity<ApiResponse<Map<String, Object>>> active(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        tokens.validate(authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(catalog.active()));
    }
}
