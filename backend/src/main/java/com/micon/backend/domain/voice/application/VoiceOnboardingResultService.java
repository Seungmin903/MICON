package com.micon.backend.domain.voice.application;

import com.micon.backend.common.exception.BusinessException;
import com.micon.backend.common.exception.ErrorCode;
import com.micon.backend.domain.preference.api.PreferenceResponse;
import com.micon.backend.domain.preference.repository.PreferenceCatalogRepository;
import com.micon.backend.domain.voice.api.VoiceOnboardingResultResponse;
import com.micon.backend.domain.voice.api.VoiceProfileRequest;
import com.micon.backend.domain.voice.api.VoiceProfileResponse;
import com.micon.backend.domain.voice.api.VoiceSessionResponse;
import com.micon.backend.domain.voice.domain.*;
import com.micon.backend.voice.api.*;
import com.micon.backend.voice.domain.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceOnboardingResultService {
    private final VoiceAnalysisSessionRepository sessions;
    private final VoiceRangeMeasurementRepository ranges;
    private final VoiceAnalysisComponentJobRepository components;
    private final SimilarArtistResultRepository similarities;
    private final PreferenceCatalogRepository catalog;
    private final UserVoiceProfileRepository profiles;
    private final ApplicationEventPublisher events;

    public VoiceOnboardingResultService(VoiceAnalysisSessionRepository sessions,
            VoiceRangeMeasurementRepository ranges, VoiceAnalysisComponentJobRepository components,
            SimilarArtistResultRepository similarities, PreferenceCatalogRepository catalog,
            UserVoiceProfileRepository profiles, ApplicationEventPublisher events) {
        this.sessions = sessions; this.ranges = ranges; this.components = components;
        this.similarities = similarities; this.catalog = catalog; this.profiles = profiles;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public VoiceOnboardingResultResponse result(long userId, long sessionId) {
        VoiceAnalysisSession session = owned(userId, sessionId);
        VoiceRangeMeasurement range = ranges.findBySessionId(sessionId).orElse(null);
        var jobs = components.findBySessionIdOrderByComponentTypeAscAttemptNoDesc(sessionId);
        Map<String, VoiceAnalysisComponentJob> latest = new LinkedHashMap<>();
        jobs.forEach(job -> latest.putIfAbsent(job.getComponentType(), job));
        var componentResults = latest.values().stream().map(job ->
                new VoiceOnboardingResultResponse.Component(job.getComponentType(), job.getStatus(),
                        job.getFailureCode(), job.getFailureReason())).toList();
        var ranked = similarities.findBySessionIdOrderByRankNo(sessionId);
        var artistsById = new HashMap<Long, PreferenceResponse.Artist>();
        catalog.findArtistsByIds(ranked.stream().map(SimilarArtistResult::getSingerId).toList())
                .forEach(artist -> artistsById.put(artist.artistId(), artist));
        var artistResults = ranked.stream().map(item -> {
            var artist = artistsById.get(item.getSingerId());
            return new VoiceOnboardingResultResponse.Artist(item.getSingerId(),
                    artist == null ? "" : artist.name(), artist == null ? null : artist.imageUrl(),
                    item.getCosineSimilarity());
        }).toList();
        return new VoiceOnboardingResultResponse(sessionId, session.getStatus(), session.getResultVersion(),
                session.getRangeStatus(), session.getTimbreStatus(), session.getArtistSearchStatus(),
                "READY_TO_SAVE".equals(session.getStatus()),
                profiles.findByUserId(userId).isPresent(), session.getExpiresAt(),
                range == null ? null : new VoiceSessionResponse.Range(range.getFullLowMidi(), range.getFullHighMidi(),
                        range.getMeasurementStatus(), range.getQualityStatus(), range.getNoteResults()), componentResults, artistResults);
    }

    @Transactional
    public VoiceProfileResponse save(long userId, long sessionId, VoiceProfileRequest.Save request) {
        VoiceAnalysisSession session = owned(userId, sessionId);
        UserVoiceProfile profile = profiles.findByUserId(userId).orElse(null);
        if (profile != null && profile.getSession().getId().equals(sessionId)
                && session.getResultVersion() == request.resultVersion()) {
            return new VoiceProfileResponse(profile.getId(), sessionId, profile.getProfileVersion());
        }
        if (session.getResultVersion() != request.resultVersion() || !"READY_TO_SAVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_VOICE_ANALYSIS_STATE);
        }
        if (profile == null) profile = profiles.save(new UserVoiceProfile(session.getUser(), session, 1));
        else if (!profile.getSession().getId().equals(sessionId)) { profile.replaceWith(session); profile = profiles.save(profile); }
        session.markSaved();
        events.publishEvent(new VoiceProfileSavedEvent(userId, profile.getId(), profile.getProfileVersion()));
        return new VoiceProfileResponse(profile.getId(), sessionId, profile.getProfileVersion());
    }

    @Transactional(readOnly = true)
    public VoiceProfileResponse latestProfile(long userId) {
        UserVoiceProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_ANALYSIS_NOT_FOUND));
        return new VoiceProfileResponse(profile.getId(), profile.getSession().getId(),
                profile.getProfileVersion());
    }

    private VoiceAnalysisSession owned(long userId, long sessionId) {
        return sessions.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_SESSION_NOT_FOUND));
    }
}
