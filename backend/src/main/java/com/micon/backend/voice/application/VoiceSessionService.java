package com.micon.backend.voice.application;

import com.micon.backend.common.exception.*;
import com.micon.backend.user.domain.*;
import com.micon.backend.voice.api.*;
import com.micon.backend.voice.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
        if (!"guided-range-v7".equals(request.protocolVersion())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지원하지 않는 음역 측정 버전입니다.");
        }
        if (request.lowMidi() != null && request.highMidi() != null
                && request.lowMidi().compareTo(request.highMidi()) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "최저음은 최고음보다 높을 수 없습니다.");
        }
        if (userId == null) return saveGuestDeviceRange(sessionId, guestToken, request);
        VoiceAnalysisSession session = sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND));
        VoiceRangeMeasurement existing = ranges.findBySessionId(sessionId).orElse(null);
        if (existing == null) {
            ranges.save(new VoiceRangeMeasurement(session, request.measurementId(), request.protocolVersion(),
                    request.lowMidi(), request.highMidi(), write(request.noteResults()),
                    request.measurementStatus(), request.qualityStatus(), request.measuredAt()));
            session.acceptRange(request.measurementStatus());
        }
        return member(session, ranges.findBySessionId(sessionId).orElse(null));
    }

    private synchronized VoiceSessionResponse.Session saveGuestDeviceRange(long sessionId, String token,
            VoiceSessionRequest.DeviceRangeResult request) {
        GuestSession guest = guest(sessionId, token);
        if (guest.range == null) {
            guest.range = new VoiceSessionResponse.Range(request.lowMidi(), request.highMidi(),
                    request.measurementStatus(), request.qualityStatus());
            guest.rangeStatus = request.measurementStatus();
            guest.status = "AVAILABLE".equals(request.measurementStatus()) ? "RANGE_IN_PROGRESS" : request.measurementStatus();
            guest.version++;
        }
        return guest.response(false);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException(ErrorCode.INVALID_REQUEST); }
    }

    public synchronized VoiceAnalysisJobResponse prepareGuestRange(long sessionId, String guestToken,
                                                                    VoiceSessionRequest.RangeAnalysisUpload request) {
        GuestSession guest = guest(sessionId, guestToken);
        GuestRangeJob existing = guestRangeJobs.get(request.requestId());
        if (existing != null) {
            if (existing.session != guest) throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 다른 분석에 사용된 requestId입니다.");
            return existing.response();
        }
        if (guest.range != null) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE,
                "이미 완료된 음역 분석이 있습니다.");
        GuestRangeJob job = new GuestRangeJob(guestIds.getAndDecrement(), guest, request.requestId(),
                request.attemptNo());
        guestRangeJobs.put(request.requestId(), job);
        guest.status = "UPLOADING";
        guest.rangeStatus = "UPLOADING";
        return job.response();
    }

    public synchronized VoiceAnalysisJobResponse queueGuestRange(String requestId, String aiJobId) {
        GuestRangeJob job = guestJob(requestId);
        job.aiJobId = aiJobId;
        job.status = "QUEUED";
        job.startedAt = LocalDateTime.now();
        job.session.status = "ANALYZING";
        job.session.rangeStatus = "ANALYZING";
        return job.response();
    }

    public synchronized void rangeDispatchUnknown(String requestId, String reason) {
        GuestRangeJob job = guestJob(requestId);
        job.status = "DISPATCH_UNKNOWN";
        job.failureCode = "AI_DISPATCH_UNKNOWN";
        job.failureReason = reason;
        job.session.status = "UPLOADING";
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
        if (request.lowMidi() == null || request.highMidi() == null
                || request.lowMidi().compareTo(request.highMidi()) > 0
                || request.noteResults() == null || request.measurementStatus() == null
                || request.qualityStatus() == null) {
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
        session.status = "UPLOADING";
        return job.response();
    }

    public synchronized VoiceAnalysisJobResponse queueGuestTimbre(
            String requestId, com.micon.backend.voice.client.VoiceAnalysisJobGateway.Result result) {
        GuestTimbreJob job = guestTimbreJob(requestId);
        if (job.aiJobId != null && !job.aiJobId.equals(result.jobId())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        job.aiJobId = result.jobId();
        job.status = "QUEUED";
        job.audioExpiresAt = result.audioExpiresAt();
        job.duplicate = result.duplicate();
        if (job.startedAt == null) job.startedAt = LocalDateTime.now();
        job.failureCode = null;
        job.failureReason = null;
        job.components.replaceAll((key, value) -> "PENDING".equals(value) ? "QUEUED" : value);
        job.session.status = "ANALYZING";
        return job.response();
    }

    public synchronized void timbreDispatchUnknown(String requestId, String reason) {
        GuestTimbreJob job = guestTimbreJob(requestId);
        if (!job.canDispatch()) return;
        job.status = "DISPATCH_UNKNOWN";
        job.failureCode = "AI_DISPATCH_UNKNOWN";
        job.failureReason = reason;
        job.session.status = "UPLOADING";
    }

    public synchronized void failGuestTimbre(String requestId, String code, String reason) {
        GuestTimbreJob job = guestTimbreJob(requestId);
        if (job.terminal()) return;
        job.status = "FAILED";
        job.failureCode = code;
        job.failureReason = reason;
        job.completedAt = LocalDateTime.now();
        job.components.replaceAll((key, value) -> isTerminalStatus(value) ? value : "FAILED");
        job.session.status = "FAILED";
    }

    private GuestTimbreJob guestTimbreJob(String requestId) {
        GuestTimbreJob job = guestTimbreJobs.get(requestId);
        if (job == null) throw new BusinessException(ErrorCode.INVALID_GUEST_SESSION);
        ensureNotExpired(job.session.expiresAt);
        return job;
    }
    public synchronized VoiceAnalysisJobResponse guestTimbreStatus(long sid,String token){GuestSession s=guest(sid,token);return guestTimbreJobs.values().stream().filter(j->j.session==s).max(java.util.Comparator.comparingLong(j->j.id)).orElseThrow(()->new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND)).response();}
    public synchronized VoiceAnalysisJobResponse cancelGuestTimbre(long sid,String token,long id){GuestSession s=guest(sid,token);GuestTimbreJob j=guestTimbreJobs.values().stream().filter(x->x.id==id&&x.session==s).findFirst().orElseThrow(()->new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));j.status="CANCELED";j.completedAt=LocalDateTime.now();s.status="CANCELED";return j.response();}
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
        job.components.put(component, request.status().name());
        if (request.status() == VoiceAnalysisComponentCallbackRequest.Status.RUNNING) {
            job.status = "RUNNING";
        } else if (job.components.values().stream().allMatch(VoiceSessionService::isTerminalStatus)) {
            boolean completed = job.components.values().stream().allMatch("COMPLETED"::equals);
            job.status = completed ? "COMPLETED" : "FAILED";
            job.completedAt = request.processedAt();
            job.session.status = completed ? "READY_TO_SAVE" : "FAILED";
            if (completed) job.session.version++;
        } else {
            job.status = "RUNNING";
        }
        return new VoiceAnalysisComponentCallbackResponse(job.id, job.status, false);
    }

    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
                || "INSUFFICIENT_DATA".equals(status) || "CANCELED".equals(status);
    }

    private VoiceSessionResponse.Session member(VoiceAnalysisSession s, VoiceRangeMeasurement r) {
        return new VoiceSessionResponse.Session(s.getId(), null, s.getStatus(), s.getRangeStatus(), s.getTimbreStatus(),
                s.getArtistSearchStatus(), s.getResultVersion(), profiles.findByUserId(s.getUser().getId()).isPresent(),
                true, s.getExpiresAt(), r == null ? null : new VoiceSessionResponse.Range(
                r.getFullLowMidi(), r.getFullHighMidi(), r.getMeasurementStatus(), r.getQualityStatus()));
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
        long version; String status="CREATED", rangeStatus="PENDING"; VoiceSessionResponse.Range range;
        GuestSession(long id, String token, LocalDateTime expiresAt) { this.id=id; this.token=token; this.expiresAt=expiresAt; }
        void accept(VoiceAnalysisComponentCallbackRequest r) {
            if (range != null) throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
            range = new VoiceSessionResponse.Range(r.lowMidi(), r.highMidi(), r.measurementStatus(), r.qualityStatus());
            rangeStatus=r.measurementStatus(); status="AVAILABLE".equals(rangeStatus)?"RANGE_IN_PROGRESS":rangeStatus; version++;
        }
        VoiceSessionResponse.Session response(boolean includeToken) {
            return new VoiceSessionResponse.Session(id, includeToken?token:null, status, rangeStatus, "PENDING", "PENDING",
                    version, false, true, expiresAt, range);
        }
    }

    private static final class GuestRangeJob {
        final long id; final GuestSession session; final String requestId; final int attemptNo;
        String aiJobId, failureCode, failureReason, status = "PENDING";
        LocalDateTime startedAt, completedAt;
        GuestRangeJob(long id, GuestSession session, String requestId, int attemptNo) {
            this.id=id; this.session=session; this.requestId=requestId; this.attemptNo=attemptNo;
        }
        void fail(String code, String reason, boolean insufficient) {
            status = insufficient ? "INSUFFICIENT_DATA" : "FAILED";
            failureCode = code; failureReason = reason; completedAt = LocalDateTime.now();
            session.rangeStatus = status; session.status = status;
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
