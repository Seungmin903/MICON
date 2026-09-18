package com.micon.backend.catalogmedia;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.domain.voice.application.AiServiceTokenValidator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public class CatalogMediaController {

    private final AiServiceTokenValidator tokens;
    private final CatalogMediaService media;

    public CatalogMediaController(AiServiceTokenValidator tokens, CatalogMediaService media) {
        this.tokens = tokens;
        this.media = media;
    }

    @GetMapping("/internal/catalog-media/vocal-stems/{songId}")
    public ResponseEntity<ApiResponse<CatalogMediaResponse>> vocalStem(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable @NotBlank @Size(max = 64) String songId) {
        tokens.validate(authorization);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(media.vocalStem(songId)));
    }
}
