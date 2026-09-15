package com.micon.backend.voice.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimilarArtistResultRepository extends JpaRepository<SimilarArtistResult, Long> {
    List<SimilarArtistResult> findBySessionIdOrderByRankNo(Long sessionId);
    void deleteBySessionId(Long sessionId);
}
