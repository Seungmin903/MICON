package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.voice.dto.VoiceSessionRequest;
import com.micon.backend.domain.voice.entity.VoiceAnalysisComponentJob;
import com.micon.backend.domain.voice.entity.VoiceAnalysisSession;
import com.micon.backend.domain.voice.entity.VoiceRangeMeasurement;
import com.micon.backend.domain.voice.entity.VoiceRecording;
import com.micon.backend.domain.voice.repository.VoiceAnalysisComponentJobRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisSessionRepository;
import com.micon.backend.domain.voice.repository.VoiceRecordingContextRepository;
import com.micon.backend.domain.voice.repository.VoiceRecordingRepository;
import com.micon.backend.domain.voice.repository.VoiceRecordingSegmentRepository;
import com.micon.backend.domain.voice.service.DirectVoiceAnalysisLifecycleService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;

class RangeAnalysisContractTest {
    @Test
    void rejectsTheSameRequestIdWhenTheRangeAudioIsDifferent() {
        VoiceAnalysisComponentJobRepository jobs = mock(VoiceAnalysisComponentJobRepository.class);
        VoiceAnalysisComponentJob existing = mock(VoiceAnalysisComponentJob.class);
        VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
        User user = mock(User.class);
        VoiceRecording recording = mock(VoiceRecording.class);
        VoiceSessionRequest.RangeAnalysisUpload request = mock(VoiceSessionRequest.RangeAnalysisUpload.class);
        when(request.requestId()).thenReturn("0199f9f5-0000-4000-8000-000000000001");
        when(jobs.findByRequestId(request.requestId())).thenReturn(Optional.of(existing));
        when(existing.getSession()).thenReturn(session);
        when(session.getId()).thenReturn(7L);
        when(session.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(3L);
        when(existing.getComponentType()).thenReturn("RANGE");
        when(existing.getRecording()).thenReturn(recording);
        when(recording.getSha256()).thenReturn("a".repeat(64));
        DirectVoiceAnalysisLifecycleService service = new DirectVoiceAnalysisLifecycleService(
                mock(VoiceAnalysisSessionRepository.class), jobs, mock(VoiceRecordingRepository.class),
                mock(VoiceRecordingContextRepository.class), mock(VoiceRecordingSegmentRepository.class),
                mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.createRangePending(3L, 7L, mock(MultipartFile.class), request,
                "b".repeat(64))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("동일한 WAV");
    }

    @Test
    void keepsServerRangeResultsDistinguishableFromDeviceResults() {
        VoiceRangeMeasurement measurement = new VoiceRangeMeasurement(
                mock(VoiceAnalysisSession.class), "measurement-1", "guided-range-v7", "SERVER_AI",
                BigDecimal.valueOf(48), BigDecimal.valueOf(72), "[]", "AVAILABLE", "VALID",
                LocalDateTime.now());

        assertThat(measurement.getSource()).isEqualTo("SERVER_AI");
    }
}
