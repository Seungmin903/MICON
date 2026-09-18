package com.micon.backend.domain.voice.application;

import com.micon.backend.domain.voice.domain.VoiceRecording;
import com.micon.backend.domain.voice.domain.VoiceRecordingDeletionJob;
import com.micon.backend.domain.voice.domain.VoiceRecordingDeletionJobRepository;
import com.micon.backend.domain.voice.domain.VoiceRecordingRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceRecordingDeletionLifecycle {
    private final VoiceRecordingDeletionJobRepository jobs;
    private final VoiceRecordingRepository recordings;

    public VoiceRecordingDeletionLifecycle(VoiceRecordingDeletionJobRepository jobs,
                                           VoiceRecordingRepository recordings) {
        this.jobs = jobs;
        this.recordings = recordings;
    }

    @Transactional
    public Long enqueue(VoiceRecording recording, LocalDateTime requestedAt) {
        VoiceRecordingDeletionJob existing = jobs.findByRecordingId(recording.getId()).orElse(null);
        if (existing != null) return existing.getId();
        recording.markDeletePending(requestedAt);
        if (!recording.hasStoredObject()) {
            recording.markDeleted(requestedAt);
            return null;
        }
        return jobs.save(new VoiceRecordingDeletionJob(recording, requestedAt)).getId();
    }

    @Transactional
    public Target claim(long jobId) {
        VoiceRecordingDeletionJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (job == null || "COMPLETED".equals(job.getStatus())) return null;
        LocalDateTime now = LocalDateTime.now();
        boolean stale = "PROCESSING".equals(job.getStatus()) && job.getUpdatedAt().isBefore(now.minusMinutes(10));
        boolean due = ("PENDING".equals(job.getStatus()) || "FAILED".equals(job.getStatus()))
                && !job.getNextAttemptAt().isAfter(now);
        if (!stale && !due) return null;
        job.claim(now);
        return new Target(job.getId(), job.getRecording().getId(), job.getRecording().getStoragePath());
    }

    @Transactional
    public void complete(long jobId) {
        VoiceRecordingDeletionJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (job == null || "COMPLETED".equals(job.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        VoiceRecording recording = recordings.findByIdForUpdate(job.getRecording().getId()).orElse(null);
        if (recording != null && recording.getDeletedAt() == null) recording.markDeleted(now);
        job.complete(now);
    }

    @Transactional
    public void fail(long jobId, String reason) {
        VoiceRecordingDeletionJob job = jobs.findByIdForUpdate(jobId).orElse(null);
        if (job == null || "COMPLETED".equals(job.getStatus())) return;
        LocalDateTime now = LocalDateTime.now();
        long delayMinutes = Math.min(60, 1L << Math.min(job.getAttemptCount(), 6));
        job.fail(reason, now.plusMinutes(delayMinutes), now);
    }

    @Transactional(readOnly = true)
    public List<Long> due(LocalDateTime now) {
        return jobs.findDueIds(now, now.minusMinutes(10)).stream().limit(50).toList();
    }

    public record Target(Long jobId, Long recordingId, String storagePath) { }
}
