package com.micon.backend.domain.voice.entity;

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
