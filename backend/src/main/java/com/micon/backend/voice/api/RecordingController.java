package com.micon.backend.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.application.RecordingUploadService;
import com.micon.backend.voice.application.RecordingQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class RecordingController {
    private final RecordingUploadService uploadService;
    private final RecordingQueryService queryService;
    public RecordingController(RecordingUploadService uploadService, RecordingQueryService queryService) {
        this.uploadService = uploadService;
        this.queryService = queryService;
    }

    @GetMapping("/recordings")
    public ResponseEntity<ApiResponse<RecordingResponse.Page>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.success(queryService.list(userId(jwt), page, size)));
    }

    @GetMapping("/recordings/{recordingId}")
    public ResponseEntity<ApiResponse<RecordingResponse.Detail>> get(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long recordingId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.get(userId(jwt), recordingId)));
    }

    @GetMapping("/recordings/{recordingId}/content")
    public ResponseEntity<ApiResponse<RecordingResponse.Playback>> playback(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long recordingId) {
        return ResponseEntity.ok(ApiResponse.success(queryService.playback(userId(jwt), recordingId)));
    }

    @PostMapping(path = "/recordings", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<RecordingUploadResponse>> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "1") short attemptNo,
            @RequestParam(defaultValue = "false") boolean keepOriginal) {
        Long id = uploadService.upload(userId(jwt), file, attemptNo, keepOriginal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new RecordingUploadResponse(id)));
    }

    private long userId(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }

    public record RecordingUploadResponse(Long recordingId) { }
}
