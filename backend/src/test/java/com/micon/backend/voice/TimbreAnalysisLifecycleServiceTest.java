package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.user.domain.User;
import com.micon.backend.domain.voice.api.VoiceAnalysisUploadRequest;
import com.micon.backend.domain.voice.application.TimbreAnalysisLifecycleService;
import com.micon.backend.domain.voice.domain.VoiceAnalysisComponentJobRepository;
import com.micon.backend.domain.voice.domain.VoiceAnalysisJob;
import com.micon.backend.domain.voice.domain.VoiceAnalysisJobRepository;
import com.micon.backend.domain.voice.domain.VoiceAnalysisMethod;
import com.micon.backend.domain.voice.domain.VoiceAnalysisSession;
import com.micon.backend.domain.voice.domain.VoiceAnalysisSessionRepository;
import com.micon.backend.domain.voice.domain.VoiceRecording;
import com.micon.backend.domain.voice.domain.VoiceRecordingContextRepository;
import com.micon.backend.domain.voice.domain.VoiceRecordingRepository;
import com.micon.backend.domain.voice.domain.VoiceRecordingSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

class TimbreAnalysisLifecycleServiceTest {
    private final VoiceAnalysisSessionRepository sessions = mock(VoiceAnalysisSessionRepository.class);
    private final VoiceAnalysisJobRepository jobs = mock(VoiceAnalysisJobRepository.class);
    private final VoiceAnalysisComponentJobRepository components = mock(VoiceAnalysisComponentJobRepository.class);
    private final TimbreAnalysisLifecycleService service = new TimbreAnalysisLifecycleService(
            sessions, jobs, components, mock(VoiceRecordingRepository.class),
            mock(VoiceRecordingContextRepository.class), mock(VoiceRecordingSegmentRepository.class),
            mock(JdbcTemplate.class));

    @Test
    void redispatchesUnknownRequestOnlyWhenTheAudioHashIsTheSame() {
        String hash = "a".repeat(64);
        VoiceAnalysisJob existing = existing(hash, true);
        when(jobs.findByRequestIdForUpdate(request().requestId())).thenReturn(Optional.of(existing));
        when(components.findByAnalysisJobIdOrderByComponentTypeAsc(31L)).thenReturn(List.of());

        var prepared = service.create(3L, 7L, file(), request(), hash);

        assertThat(prepared.analysisId()).isEqualTo(31L);
        assertThat(prepared.shouldDispatch()).isTrue();
    }

    @Test
    void rejectsTheSameRequestIdWhenTheAudioHashIsDifferent() {
        VoiceAnalysisJob existing = existing("a".repeat(64), true);
        when(jobs.findByRequestIdForUpdate(request().requestId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(3L, 7L, file(), request(), "b".repeat(64)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    private VoiceAnalysisJob existing(String hash, boolean canDispatch) {
        VoiceAnalysisJob job = mock(VoiceAnalysisJob.class);
        VoiceAnalysisSession session = mock(VoiceAnalysisSession.class);
        VoiceRecording recording = mock(VoiceRecording.class);
        User user = mock(User.class);
        when(job.getId()).thenReturn(31L);
        when(job.getSession()).thenReturn(session);
        when(job.getRecording()).thenReturn(recording);
        when(job.getRequestId()).thenReturn(request().requestId());
        when(job.getAudioSha256()).thenReturn(hash);
        when(job.getAttemptNo()).thenReturn(1);
        when(job.getStatus()).thenReturn("DISPATCH_UNKNOWN");
        when(job.canDispatch()).thenReturn(canDispatch);
        when(session.getId()).thenReturn(7L);
        when(session.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(3L);
        when(recording.getId()).thenReturn(8L);
        return job;
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "sample.wav", "audio/wav", new byte[] {1});
    }

    private VoiceAnalysisUploadRequest request() {
        return new VoiceAnalysisUploadRequest("0199f9f5-7e6a-7a20-bc91-23b8c1ed0a11",
                VoiceAnalysisMethod.SONG, "21", "v4", LocalDateTime.now(), 30_000, 48_000,
                "HIGHLIGHT", 10_000, 10_200, "inst-v2", "lyrics-v3", List.of());
    }
}
