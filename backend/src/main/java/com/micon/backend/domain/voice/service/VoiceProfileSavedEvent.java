package com.micon.backend.domain.voice.service;

public record VoiceProfileSavedEvent(long userId, long profileId, long profileVersion) {
}
