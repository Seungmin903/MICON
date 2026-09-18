package com.micon.backend.domain.voice.application;

import com.micon.backend.domain.voice.client.ArtistSearchJobGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.ResourceAccessException;

@Component
public class ArtistSearchDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ArtistSearchDispatcher.class);

    private final ArtistSearchJobService jobs;
    private final ArtistSearchJobGateway gateway;
    private final boolean enabled;

    public ArtistSearchDispatcher(
            ArtistSearchJobService jobs,
            ArtistSearchJobGateway gateway,
            @Value("${ai.artist-search-enabled:false}") boolean enabled
    ) {
        this.jobs = jobs;
        this.gateway = gateway;
        this.enabled = enabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void dispatch(ArtistSearchRequested event) {
        if (!enabled) {
            jobs.disabled(event.analysisId());
            return;
        }
        ArtistSearchJobService.Prepared prepared;
        try {
            prepared = jobs.prepare(event.analysisId());
        } catch (RuntimeException exception) {
            jobs.failSession(event.analysisId());
            log.warn("Unable to prepare artist search for analysis {}", event.analysisId(), exception);
            return;
        }
        if (!prepared.shouldDispatch()) return;
        try {
            jobs.queued(prepared.jobId(), gateway.submit(prepared.command()));
        } catch (ResourceAccessException exception) {
            jobs.dispatchUnknown(prepared.jobId(), exception.getMessage());
        } catch (RuntimeException exception) {
            jobs.failed(prepared.jobId(), "ARTIST_SEARCH_DISPATCH_FAILED", exception.getMessage());
        }
    }
}
