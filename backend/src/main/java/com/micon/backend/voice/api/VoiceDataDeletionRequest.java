package com.micon.backend.voice.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record VoiceDataDeletionRequest(
        @NotNull(message = "삭제 확인이 필요합니다.")
        @AssertTrue(message = "삭제에 동의해야 합니다.")
        Boolean confirmed
) {
}
