package com.micon.backend.domain.voice.service;

import com.micon.backend.domain.user.repository.UserRepository;
import com.micon.backend.domain.voice.dto.VoiceAnalysisComponentCallbackRequest;
import com.micon.backend.domain.voice.dto.VoiceAnalysisComponentCallbackResponse;
import com.micon.backend.domain.voice.dto.VoiceAnalysisJobResponse;
import com.micon.backend.domain.voice.dto.VoiceSessionRequest;
import com.micon.backend.domain.voice.dto.VoiceSessionResponse;
import com.micon.backend.domain.voice.entity.VoiceAnalysisSession;
import com.micon.backend.domain.voice.entity.VoiceRangeMeasurement;
import com.micon.backend.domain.voice.repository.UserVoiceProfileRepository;
import com.micon.backend.domain.voice.repository.VoiceAnalysisSessionRepository;
import com.micon.backend.domain.voice.repository.VoiceRangeMeasurementRepository;

import com.micon.backend.common.exception.*;
import com.micon.backend.domain.user.entity.User;
import com.micon.backend.domain.user.entity.UserStatus;
import com.micon.backend.domain.voice.client.VoiceAnalysisJobGateway;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class VoiceSessionService {
    private static final Duration TTL = Duration.ofHours(2);
    private static final Set<String> ACTIVE = Set.of("CREATED", "RANGE_IN_PROGRESS", "TIMBRE_RECORDING",
            "UPLOADING", "ANALYZING", "PARTIAL_READY", "READY_TO_SAVE", "SAVING", "FAILED",
            "INSUFFICIENT_DATA");
    private final VoiceAnalysisSessionRepository sessions;
    private final VoiceRangeMeasurementRepository ranges;
    private final UserVoiceProfileRepository profiles;
    private final UserRepository users;
    private final ObjectMapper json;
    private final AtomicLong guestIds = new AtomicLong(-1);
    private final Map<Long, GuestSession> guests = new ConcurrentHashMap<>();
    private final Map<String, GuestRangeJob> guestRangeJobs = new ConcurrentHashMap<>();
    private final Map<String, GuestTimbreJob> guestTimbreJobs = new ConcurrentHashMap<>();

    public VoiceSessionService(VoiceAnalysisSessionRepository sessions, VoiceRangeMeasurementRepository ranges,
                               UserVoiceProfileRepository profiles, UserRepository users, ObjectMapper json) {
        this.sessions = sessions; this.ranges = ranges; this.profiles = profiles; this.users = users; this.json = json;
    }

    @Transactional
    public VoiceSessionResponse.Session create(Long userId) {
        LocalDateTime expiresAt = LocalDateTime.now().plus(TTL);
        if (userId != null) {
            User user = users.findByIdAndStatus(userId, UserStatus.ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE));
            return member(sessions.saveAndFlush(new VoiceAnalysisSession(user, null, expiresAt)), null);
        }
        long id = guestIds.getAndDecrement();
        String token = UUID.randomUUID().toString();
        GuestSession guest = new GuestSession(id, token, expiresAt);
        guests.put(id, guest);
        return guest.response(true);
    }

    @Transactional(readOnly = true)
    public VoiceSessionResponse.Session active(Long userId, String guestToken) {
        if (userId != null) {
            VoiceAnalysisSession session = sessions.findFirstByUserIdAndStatusInOrderByCreatedAtDesc(userId, ACTIVE)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND));
            return member(session, ranges.findBySessionId(session.getId()).orElse(null));
        }
        return guestByToken(guestToken).response(true);
    }

    @Transactional
    public VoiceSessionResponse.Session saveDeviceRange(Long userId, String guestToken, long sessionId,
            VoiceSessionRequest.DeviceRangeResult request) {
        if (!java.util.Set.of("guided-range-v7", "guided-range-v8").contains(request.protocolVersion())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 음역 측정 버전입니다.");
        }
        validateDeviceRange(request);
        if (userId == null) return saveGuestDeviceRange(sessionId, guestToken, request);
        VoiceAnalysisSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND));
        if (session.isExpired() || "CANCELED".equals(session.getStatus()) || "SAVED".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        VoiceRangeMeasurement existing = ranges.findBySessionId(sessionId).orElse(null);
        if (existing == null) {
            if (ranges.findByMeasurementUid(request.measurementId()).isPresent()) {
                throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE,
                        "이미 다른 세션에 저장된 음역 측정 결과입니다.");
            }
            ranges.save(new VoiceRangeMeasurement(session, request.measurementId(), request.protocolVersion(),
                    request.lowMidi(), request.highMidi(), write(request.noteResults()),
                    request.measurementStatus(), request.qualityStatus(), request.measuredAt()));
            session.acceptRange(request.measurementStatus());
        } else if (!Objects.equals(existing.getMeasurementUid(), request.measurementId())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE,
                    "이미 다른 음역 측정 결과가 저장된 세션입니다.");
        } else if (!sameRangeResult(existing, request)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "같은 measurementId에는 동일한 음역 결과만 재전송할 수 있습니다.");
        }
        return member(session, ranges.findBySessionId(sessionId).orElse(null));
    }

    private synchronized VoiceSessionResponse.Session saveGuestDeviceRange(long sessionId, String token,
            VoiceSessionRequest.DeviceRangeResult request) {
        GuestSession guest = guest(sessionId, token);
        String fingerprint = rangeFingerprint(request);
        if (guest.range == null) {
            guest.rangeMeasurementId = request.measurementId();
            guest.range = new VoiceSessionResponse.Range(request.lowMidi(), request.highMidi(),
                    request.measurementStatus(), request.qualityStatus(), null,
                    request.protocolVersion(), null, null);
            guest.rangeStatus = request.measurementStatus();
            guest.rangeFingerprint = fingerprint;
            guest.refreshStatus();
            guest.version++;
        } else if (!Objects.equals(guest.rangeMeasurementId, request.measurementId())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE,
                    "이미 다른 음역 측정 결과가 저장된 세션입니다.");
        } else if (!Objects.equals(guest.rangeFingerprint, fingerprint)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "같은 measurementId에는 동일한 음역 결과만 재전송할 수 있습니다.");
        }
        return guest.response(false);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR); }
    }

    private void validateDeviceRange(VoiceSessionRequest.DeviceRangeResult request) {
        boolean available = "AVAILABLE".equals(request.measurementStatus());
        if (available && (request.lowMidi() == null || request.highMidi() == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "AVAILABLE 음역 결과에는 최저음과 최고음이 필요합니다.");
        }
        if (request.lowMidi() != null && (request.lowMidi().compareTo(BigDecimal.ZERO) < 0
                || request.lowMidi().compareTo(BigDecimal.valueOf(127)) > 0)
                || request.highMidi() != null && (request.highMidi().compareTo(BigDecimal.ZERO) < 0
                || request.highMidi().compareTo(BigDecimal.valueOf(127)) > 0)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "MIDI 값은 0에서 127 사이여야 합니다.");
        }
        if (request.lowMidi() != null && request.highMidi() != null
                && request.lowMidi().compareTo(request.highMidi()) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "최저음은 최고음보다 높을 수 없습니다.");
        }
        if (request.noteResults() == null || request.noteResults().isNull()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "음역 측정 결과가 필요합니다.");
        }
    }

    private boolean sameRangeResult(VoiceRangeMeasurement existing,
                                   VoiceSessionRequest.DeviceRangeResult request) {
        return Objects.equals(existing.getFullLowMidi(), request.lowMidi())
                && Objects.equals(existing.getFullHighMidi(), request.highMidi())
                && Objects.equals(existing.getMeasurementStatus(), request.measurementStatus())
                && Objects.equals(existing.getQualityStatus(), request.qualityStatus())
                && Objects.equals(existing.getNoteResults(), write(request.noteResults()));
    }

    private String rangeFingerprint(VoiceSessionRequest.DeviceRangeResult request) {
        return String.join("|", request.measurementId(), String.valueOf(request.lowMidi()),
                String.valueOf(request.highMidi()), request.measurementStatus(), request.qualityStatus(),
                write(request.noteResults()));
    }

    public synchronized VoiceAnalysisJobResponse prepareGuestRange(long sessionId, String guestToken,
                                                                   VoiceSessionRequest.RangeAnalysisUpload request,
                                                                   String audioSha256) {
        GuestSession guest = guest(sessionId, guestToken);
        GuestRangeJob existing = guestRangeJobs.get(request.requestId());
        if (existing != null) {
            if (existing.session != guest) throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 다른 분석에 사용된 requestId입니다.");
            if (!Objects.equals(existing.audioSha256, audioSha256)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "같은 requestId에는 동일한 WAV만 재전송할 수 있습니다.");
            }
            return existing.response();
        }
        if (guest.range != null) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE,
                "이미 완료된 음역 분석이 있습니다.");
        GuestRangeJob job = new GuestRangeJob(guestIds.getAndDecrement(), guest, request.requestId(),
                request.attemptNo(), audioSha256);
        guestRangeJobs.put(request.requestId(), job);
        guest.rangeStatus = "UPLOADING";
        guest.refreshStatus();
        return job.response();
    }

    public synchronized VoiceAnalysisJobResponse queueGuestRange(String requestId, String aiJobId) {
        GuestRangeJob job = guestJob(requestId);
        job.aiJobId = aiJobId;
        job.status = "QUEUED";
        job.startedAt = LocalDateTime.now();
        job.session.rangeStatus = "ANALYZING";
        job.session.refreshStatus();
        return job.response();
    }

    public synchronized void rangeDispatchUnknown(String requestId, String reason) {
        GuestRangeJob job = guestJob(requestId);
        job.status = "DISPATCH_UNKNOWN";
        job.failureCode = "AI_DISPATCH_UNKNOWN";
        job.failureReason = reason;
        job.session.rangeStatus = "UPLOADING";
        job.session.refreshStatus();
    }

    public synchronized VoiceAnalysisComponentCallbackResponse acceptGuestRangeCallback(
            VoiceAnalysisComponentCallbackRequest request) {
        GuestRangeJob job = guestRangeJobs.get(request.requestId());
        if (job == null) return null;
        ensureNotExpired(job.session.expiresAt);
        if (job.aiJobId != null && !job.aiJobId.equals(request.jobId()))
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        if (request.attemptNo() != job.attemptNo)
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        job.aiJobId = request.jobId();
        if ("COMPLETED".equals(job.status) || "FAILED".equals(job.status)
                || "INSUFFICIENT_DATA".equals(job.status)) {
            return new VoiceAnalysisComponentCallbackResponse(job.id, job.status, true);
        }
        switch (request.status()) {
            case RUNNING -> job.status = "RUNNING";
            case COMPLETED -> {
                validateRangeResult(request);
                job.status = "COMPLETED";
                job.session.accept(request);
            }
            case FAILED -> job.fail(request.failureCode(), request.failureReason(), false);
            case INSUFFICIENT_DATA -> job.fail(request.failureCode(), request.failureReason(), true);
        }
        return new VoiceAnalysisComponentCallbackResponse(job.id, job.status, false);
    }

    private void validateRangeResult(VoiceAnalysisComponentCallbackRequest request) {
        boolean available = "AVAILABLE".equals(request.measurementStatus());
        if ((available && (request.lowMidi() == null || request.highMidi() == null))
                || request.lowMidi() != null && (request.lowMidi().compareTo(BigDecimal.ZERO) < 0
                || request.lowMidi().compareTo(BigDecimal.valueOf(127)) > 0)
                || request.highMidi() != null && (request.highMidi().compareTo(BigDecimal.ZERO) < 0
                || request.highMidi().compareTo(BigDecimal.valueOf(127)) > 0)
                || request.lowMidi() != null && request.highMidi() != null
                && request.lowMidi().compareTo(request.highMidi()) > 0
                || request.noteResults() == null || request.noteResults().isNull()
                || !Set.of("AVAILABLE", "INSUFFICIENT_DATA", "FAILED").contains(request.measurementStatus())
                || !Set.of("VALID", "NOISY", "CLIPPED", "INSUFFICIENT_VOICE").contains(request.qualityStatus())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
    }

    private GuestRangeJob guestJob(String requestId) {
        GuestRangeJob job = guestRangeJobs.get(requestId);
        if (job == null) throw new BusinessException(ErrorCode.INVALID_GUEST_SESSION);
        ensureNotExpired(job.session.expiresAt);
        return job;
    }

    public synchronized VoiceAnalysisJobResponse prepareGuestTimbre(long sessionId, String token,
            String requestId, int attemptNo, String audioSha256) {
        GuestSession session = guest(sessionId, token);
        GuestTimbreJob existing = guestTimbreJobs.get(requestId);
        if (existing != null) {
            if (existing.session != session || !existing.audioSha256.equals(audioSha256)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return existing.response();
        }
        GuestTimbreJob job = new GuestTimbreJob(guestIds.getAndDecrement(), session, requestId,
                attemptNo, audioSha256);
        guestTimbreJobs.put(requestId, job);
        session.timbreStatus = "UPLOADING";
        session.refreshStatus();
        return job.response();
    }

    public synchronized VoiceAnalysisJobResponse queueGuestTimbre(
            String requestId, VoiceAnalysisJobGateway.Result result) {
        GuestTimbreJob job = guestTimbreJob(requestId);
        if (job.aiJobId != null && !job.aiJobId.equals(result.jobId())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        job.aiJobId = result.jobId();
        job.audioExpiresAt = result.audioExpiresAt();
        job.duplicate = result.duplicate();
        if (job.terminal()) return job.response();
        job.status = "RUNNING".equals(job.status) || "RUNNING".equals(result.status()) ? "RUNNING" : "QUEUED";
        if (job.startedAt == null) job.startedAt = LocalDateTime.now();
        job.failureCode = null;
        job.failureReason = null;
        job.components.replaceAll((key, value) -> "PENDING".equals(value) ? "QUEUED" : value);
        job.session.timbreStatus = "ANALYZING";
        job.session.refreshStatus();
        return job.response();
    }

    public synchronized void timbreDispatchUnknown(String requestId, String reason) {
        GuestTimbreJob job = guestTimbreJob(requestId);
        if (!job.canDispatch()) return;
        job.status = "DISPATCH_UNKNOWN";
        job.failureCode = "AI_DISPATCH_UNKNOWN";
        job.failureReason = reason;
        job.session.timbreStatus = "UPLOADING";
        job.session.refreshStatus();
    }

    public synchronized void failGuestTimbre(String requestId, String code, String reason) {
        GuestTimbreJob job = guestTimbreJob(requestId);
        if (job.terminal()) return;
        job.status = "FAILED";
        job.failureCode = code;
        job.failureReason = reason;
        job.completedAt = LocalDateTime.now();
        job.components.replaceAll((key, value) -> isTerminalStatus(value) ? value : "FAILED");
        job.session.timbreStatus = "FAILED";
        job.session.refreshStatus();
    }

    private GuestTimbreJob guestTimbreJob(String requestId) {
        GuestTimbreJob job = guestTimbreJobs.get(requestId);
        if (job == null) throw new BusinessException(ErrorCode.INVALID_GUEST_SESSION);
        ensureNotExpired(job.session.expiresAt);
        return job;
    }
    public synchronized VoiceAnalysisJobResponse guestTimbreStatus(long sid,String token){GuestSession s=guest(sid,token);return guestTimbreJobs.values().stream().filter(j->j.session==s).max(java.util.Comparator.comparingLong(j->j.id)).orElseThrow(()->new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND)).response();}
    public synchronized VoiceAnalysisJobResponse cancelGuestTimbre(long sid, String token, long id) {
        GuestSession session = guest(sid, token);
        GuestTimbreJob job = guestTimbreJobs.values().stream()
                .filter(item -> item.id == id && item.session == session).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        job.status = "CANCELED";
        job.completedAt = LocalDateTime.now();
        session.timbreStatus = "FAILED";
        session.refreshStatus();
        return job.response();
    }
    public synchronized VoiceAnalysisComponentCallbackResponse acceptGuestTimbreCallback(
            VoiceAnalysisComponentCallbackRequest request) {
        GuestTimbreJob job = guestTimbreJobs.get(request.requestId());
        if (job == null) return null;
        ensureNotExpired(job.session.expiresAt);
        String component = request.component() == null || request.component().isBlank()
                ? request.modelKey() : request.component();
        if (request.attemptNo() == null || job.attemptNo != request.attemptNo()
                || (job.aiJobId != null && !job.aiJobId.equals(request.jobId()))
                || component == null || !job.components.containsKey(component)) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
        if (job.aiJobId == null) job.aiJobId = request.jobId();
        if (isTerminalStatus(job.components.get(component))) {
            return new VoiceAnalysisComponentCallbackResponse(job.id, job.status, true);
        }
        if (request.status() == VoiceAnalysisComponentCallbackRequest.Status.COMPLETED) {
            validateGuestTimbreComponent(component, request);
        }
        job.components.put(component, request.status().name());
        if (request.status() == VoiceAnalysisComponentCallbackRequest.Status.RUNNING) {
            job.status = "RUNNING";
        } else if (job.components.values().stream().allMatch(VoiceSessionService::isTerminalStatus)) {
            boolean completed = job.components.values().stream().allMatch("COMPLETED"::equals);
            job.status = completed ? "COMPLETED" : "FAILED";
            job.completedAt = request.processedAt();
            job.session.timbreStatus = completed ? "AVAILABLE" : "FAILED";
            job.session.refreshStatus();
            if (completed) job.session.version++;
        } else {
            job.status = "RUNNING";
        }
        return new VoiceAnalysisComponentCallbackResponse(job.id, job.status, false);
    }

    private void validateGuestTimbreComponent(String component,
            VoiceAnalysisComponentCallbackRequest request) {
        int expectedDimension = "ECAPA".equals(component) ? 192 : "SINGER_BYOL".equals(component) ? 1000 : -1;
        List<Double> embedding = request.embedding();
        var validChunkCount = request.segmentInfo() == null ? null : request.segmentInfo().get("validChunkCount");
        double energy = embedding == null ? 0 : embedding.stream()
                .filter(Objects::nonNull).mapToDouble(value -> value * value).sum();
        if (expectedDimension < 0 || !component.equals(request.modelKey())
                || request.modelVersion() == null || request.modelVersion().isBlank()
                || request.dimension() == null || request.dimension() != expectedDimension
                || embedding == null || embedding.size() != expectedDimension
                || embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))
                || !Double.isFinite(energy) || energy < 1e-12
                || validChunkCount == null || !validChunkCount.isIntegralNumber()
                || validChunkCount.asInt() < 5) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_CALLBACK);
        }
    }

    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "INSUFFICIENT_DATA".equals(status) || "CANCELED".equals(status);
    }

    private VoiceSessionResponse.Session member(VoiceAnalysisSession s, VoiceRangeMeasurement r) {
        return new VoiceSessionResponse.Session(s.getId(), null, s.getStatus(), s.getRangeStatus(), s.getTimbreStatus(),
                s.getArtistSearchStatus(), s.getResultVersion(), profiles.findByUserId(s.getUser().getId()).isPresent(),
                true, s.getExpiresAt(), r == null ? null : new VoiceSessionResponse.Range(
                r.getFullLowMidi(), r.getFullHighMidi(), r.getMeasurementStatus(), r.getQualityStatus(),
                r.getNoteResults(), r.getRuleVersion(), r.getStableLowMidi(), r.getStableHighMidi()));
    }

    private GuestSession guestByToken(String token) {
        if (token == null || token.isBlank()) throw new BusinessException(ErrorCode.INVALID_GUEST_SESSION);
        GuestSession guest = guests.values().stream().filter(g -> g.token.equals(token)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_GUEST_SESSION));
        ensureNotExpired(guest.expiresAt);
        return guest;
    }
    private GuestSession guest(long id, String token) {
        GuestSession g = guests.get(id);
        if (g == null || token == null || !g.token.equals(token)) throw new BusinessException(ErrorCode.INVALID_GUEST_SESSION);
        ensureNotExpired(g.expiresAt); return g;
    }
    private void ensureNotExpired(LocalDateTime expiresAt) {
        if (!expiresAt.isAfter(LocalDateTime.now())) throw new BusinessException(ErrorCode.VOICE_SESSION_EXPIRED);
    }

    private static final class GuestSession {
        final long id; final String token; final LocalDateTime expiresAt;
        long version; String status="CREATED", rangeStatus="PENDING", timbreStatus="PENDING";
        String rangeMeasurementId, rangeFingerprint;
        VoiceSessionResponse.Range range;
        GuestSession(long id, String token, LocalDateTime expiresAt) { this.id=id; this.token=token; this.expiresAt=expiresAt; }
        void accept(VoiceAnalysisComponentCallbackRequest r) {
            if (range != null) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
            range = new VoiceSessionResponse.Range(r.lowMidi(), r.highMidi(), r.measurementStatus(), r.qualityStatus());
            rangeStatus=r.measurementStatus(); refreshStatus(); version++;
        }
        VoiceSessionResponse.Session response(boolean includeToken) {
            return new VoiceSessionResponse.Session(id, includeToken?token:null, status, rangeStatus, timbreStatus, "PENDING",
                    version, false, true, expiresAt, range);
        }
        void refreshStatus() {
            if ("AVAILABLE".equals(rangeStatus) && "AVAILABLE".equals(timbreStatus)) status = "READY_TO_SAVE";
            else if ("AVAILABLE".equals(rangeStatus) || "AVAILABLE".equals(timbreStatus)) status = "PARTIAL_READY";
            else if ("FAILED".equals(rangeStatus) || "FAILED".equals(timbreStatus)) status = "FAILED";
            else if ("INSUFFICIENT_DATA".equals(rangeStatus) || "INSUFFICIENT_DATA".equals(timbreStatus)) status = "INSUFFICIENT_DATA";
            else if ("UPLOADING".equals(rangeStatus) || "UPLOADING".equals(timbreStatus)) status = "UPLOADING";
            else if ("ANALYZING".equals(rangeStatus) || "ANALYZING".equals(timbreStatus)) status = "ANALYZING";
            else status = "CREATED";
        }
    }

    private static final class GuestRangeJob {
        final long id; final GuestSession session; final String requestId; final int attemptNo;
        String aiJobId, failureCode, failureReason, status = "PENDING";
        LocalDateTime startedAt, completedAt;
        final String audioSha256;
        GuestRangeJob(long id, GuestSession session, String requestId, int attemptNo, String audioSha256) {
            this.id=id; this.session=session; this.requestId=requestId; this.attemptNo=attemptNo;
            this.audioSha256=audioSha256;
        }
        void fail(String code, String reason, boolean insufficient) {
            status = insufficient ? "INSUFFICIENT_DATA" : "FAILED";
            failureCode = code; failureReason = reason; completedAt = LocalDateTime.now();
            session.rangeStatus = status; session.refreshStatus();
        }
        VoiceAnalysisJobResponse response() {
            return new VoiceAnalysisJobResponse(id, session.id, null, requestId, aiJobId, "RANGE", status,
                    failureCode, failureReason, "FAILED".equals(status), startedAt, completedAt,
                    null, false, java.util.List.of());
        }
    }
    private static final class GuestTimbreJob {
        final long id;
        final GuestSession session;
        final String requestId;
        final int attemptNo;
        final String audioSha256;
        String aiJobId;
        String status = "PENDING";
        String failureCode;
        String failureReason;
        LocalDateTime startedAt;
        LocalDateTime completedAt;
        LocalDateTime audioExpiresAt;
        boolean duplicate;
        final Map<String, String> components = new HashMap<>(
                Map.of("ECAPA", "PENDING", "SINGER_BYOL", "PENDING"));

        GuestTimbreJob(long id, GuestSession session, String requestId, int attemptNo, String audioSha256) {
            this.id = id;
            this.session = session;
            this.requestId = requestId;
            this.attemptNo = attemptNo;
            this.audioSha256 = audioSha256;
        }

        boolean canDispatch() {
            return "PENDING".equals(status) || "DISPATCH_UNKNOWN".equals(status);
        }

        boolean terminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELED".equals(status);
        }

        VoiceAnalysisJobResponse response() {
            java.util.List<VoiceAnalysisJobResponse.Component> details = components.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new VoiceAnalysisJobResponse.Component(
                            null, entry.getKey(), entry.getValue(), null, null, null))
                    .toList();
            return new VoiceAnalysisJobResponse(id, session.id, null, requestId, aiJobId, "TIMBRE", status,
                    failureCode, failureReason, "FAILED".equals(status), startedAt, completedAt,
                    audioExpiresAt, duplicate, details);
        }
    }
}
