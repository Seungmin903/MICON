package com.micon.backend.domain.voice.domain;

public enum VoiceAnalysisStatus {
    READY,
    RECORDING,
    UPLOADING,
    ANALYZING,
    COMPLETED,
    FAILED,
    CANCELED,
    INSUFFICIENT_DATA
}
