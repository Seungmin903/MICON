package com.micon.backend.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.application.RecordingUploadService;
import com.micon.backend.voice.application.VoiceDataDeletionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class RecordingController {
    private final RecordingUploadService service;
    private final VoiceDataDeletionService deletionService;
    public RecordingController(RecordingUploadService service, VoiceDataDeletionService deletionService) {
        this.service = service;
        this.deletionService = deletionService;
    }

    @DeleteMapping("/recordings/{recordingId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal Jwt jwt, @PathVariable long recordingId) {
        deletionService.deleteRecording(userId(jwt), recordingId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping(path = "/recordings", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<RecordingUploadResponse>> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "1") short attemptNo,
            @RequestParam(defaultValue = "false") boolean keepOriginal) {
        Long id = service.upload(userId(jwt), file, attemptNo, keepOriginal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new RecordingUploadResponse(id)));
    }

    private long userId(Jwt jwt) {
        try { return Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
    }

    public record RecordingUploadResponse(Long recordingId) { }
}
