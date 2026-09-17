package com.micon.backend.voice;

import static org.mockito.Mockito.*;

import com.micon.backend.voice.application.ArtistSearchDispatcher;
import com.micon.backend.voice.application.ArtistSearchJobService;
import com.micon.backend.voice.application.ArtistSearchRequested;
import com.micon.backend.voice.client.ArtistSearchJobGateway;
import org.junit.jupiter.api.Test;

class ArtistSearchDispatcherTest {
    private final ArtistSearchJobService jobs = mock(ArtistSearchJobService.class);
    private final ArtistSearchJobGateway gateway = mock(ArtistSearchJobGateway.class);

    @Test
    void recordsDisabledStatusInsteadOfLeavingArtistSearchPending() {
        var dispatcher = new ArtistSearchDispatcher(jobs, gateway, false);

        dispatcher.dispatch(new ArtistSearchRequested(31L));

        verify(jobs).disabled(31L);
        verifyNoInteractions(gateway);
    }
}
