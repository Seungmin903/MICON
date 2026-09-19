package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.SimilarArtistResult;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SimilarArtistResultRepository extends JpaRepository<SimilarArtistResult, Long> {
    List<SimilarArtistResult> findBySessionIdOrderByRankNo(Long sessionId);
    void deleteBySessionId(Long sessionId);
}
