package com.micon.backend.voice.api;

import com.micon.backend.common.api.ApiResponse;
import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.application.RecordingUploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class RecordingController {
    private final RecordingUploadService service;
    public RecordingController(RecordingUploadService service) { this.service = service; }

    @PostMapping(path = "/recordings", consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<RecordingUploadResponse>> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "1") short attemptNo,
            @RequestParam(defaultValue = "false") boolean keepOriginal) {
        long userId;
        try { userId = Long.parseLong(jwt.getSubject()); }
        catch (NumberFormatException exception) { throw new BusinessException(ErrorCode.UNAUTHENTICATED); }
        Long id = service.upload(userId, file, attemptNo, keepOriginal);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new RecordingUploadResponse(id)));
    }

    public record RecordingUploadResponse(Long recordingId) { }
}
