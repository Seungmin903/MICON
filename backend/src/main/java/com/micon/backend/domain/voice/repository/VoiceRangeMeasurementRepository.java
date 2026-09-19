package com.micon.backend.domain.voice.repository;

import com.micon.backend.domain.voice.entity.VoiceRangeMeasurement;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoiceRangeMeasurementRepository extends JpaRepository<VoiceRangeMeasurement, Long> {
    Optional<VoiceRangeMeasurement> findBySessionId(Long sessionId);
    Optional<VoiceRangeMeasurement> findByMeasurementUid(String measurementUid);
}
