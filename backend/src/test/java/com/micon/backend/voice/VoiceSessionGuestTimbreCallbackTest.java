package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.dto.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisSessionRepository;
import com.micon.backend.domain.voice.repository.VoiceRangeMeasurementRepository;
import com.micon.backend.domain.voice.service.VoiceSessionService;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VoiceSessionGuestTimbreCallbackTest {
    private final ObjectMapper json = new ObjectMapper();
    private final VoiceSessionService service = new VoiceSessionService(
            mock(VoiceAnalysisSessionRepository.class), mock(VoiceRangeMeasurementRepository.class),
            mock(UserVoiceProfileRepository.class), mock(UserRepository.class), json);

    @Test
    void acceptsFourSegmentsForBothGuestComponentsButRejectsThree() {
        var session = service.create(null);
        service.prepareGuestTimbre(session.sessionId(), session.guestSessionToken(),
                "guest-request", 1, "audio-sha");

        assertThatThrownBy(() -> service.acceptGuestTimbreCallback(callback("ECAPA", 192, 3)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.guestTimbreStatus(session.sessionId(), session.guestSessionToken()).status())
                .isEqualTo("PENDING");

        var ecapa = service.acceptGuestTimbreCallback(callback("ECAPA", 192, 4));
        assertThat(ecapa.status()).isEqualTo("RUNNING");
        var byol = service.acceptGuestTimbreCallback(callback("SINGER_BYOL", 1000, 4));
        assertThat(byol.status()).isEqualTo("COMPLETED");
        assertThat(byol.duplicate()).isFalse();
    }

    private VoiceAnalysisComponentCallbackRequest callback(String component, int dimension, int validChunkCount) {
        return new VoiceAnalysisComponentCallbackRequest(
                "guest-request", "ai-guest-job", component,
                VoiceAnalysisComponentCallbackRequest.Status.COMPLETED,
                component, "model-v1", dimension, Collections.nCopies(dimension, 0.125),
                json.createObjectNode().put("validChunkCount", validChunkCount), null,
                null, null, LocalDateTime.now(), 1,
                null, null, null, null, null, null, null, null, null, null);
    }
}
