package com.micon.backend.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.micon.backend.voice.application.ArtistSearchJobService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ArtistSearchTransactionBoundaryTest {
    @Test
    void afterCommitPersistenceUsesIndependentTransactions() throws Exception {
        List<Method> methods = List.of(
                ArtistSearchJobService.class.getMethod("prepare", long.class),
                ArtistSearchJobService.class.getMethod("queued", long.class,
                        com.micon.backend.voice.client.ArtistSearchJobGateway.Result.class),
                ArtistSearchJobService.class.getMethod("dispatchUnknown", long.class, String.class),
                ArtistSearchJobService.class.getMethod("failed", long.class, String.class, String.class),
                ArtistSearchJobService.class.getMethod("failSession", long.class),
                ArtistSearchJobService.class.getMethod("disabled", long.class));
        for (Method candidate : methods) {
            assertThat(candidate.getAnnotation(Transactional.class).propagation())
                    .as(candidate.getName())
                    .isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
