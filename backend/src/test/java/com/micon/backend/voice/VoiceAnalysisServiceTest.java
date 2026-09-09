package com.micon.backend.voice;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.voice.api.VoiceAnalysisRequest;
import com.micon.backend.voice.application.VoiceAnalysisLifecycleService;
import com.micon.backend.voice.application.VoiceAnalysisService;
import com.micon.backend.voice.client.VoiceAnalysisJobGateway;
import com.micon.backend.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.voice.domain.VoiceAnalysisRepository;
import com.micon.backend.voice.storage.VoiceStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class VoiceAnalysisServiceTest {
    @Mock VoiceAnalysisRepository analyses;
    @Mock VoiceAnalysisLifecycleService lifecycle;
    @Mock VoiceAnalysisJobGateway gateway;
    @Mock VoiceStorage storage;

    @Test
    void rejectsCreationBeforePersistingStateWhenIntegrationIsDisabled() {
        VoiceAnalysisService service = new VoiceAnalysisService(
                analyses, lifecycle, gateway, storage, false, "v1", Duration.ofMinutes(30));

        assertThatThrownBy(() -> service.create(
                1L, new VoiceAnalysisRequest(2L, VoiceAnalysisMethod.SCALE, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.VOICE_ANALYSIS_DISPATCH_FAILED));
        verifyNoInteractions(analyses, lifecycle, gateway, storage);
    }
}
