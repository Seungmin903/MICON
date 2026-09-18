package com.micon.backend.domain.voice.application;

public record VoiceProfileSavedEvent(long userId, long profileId, long profileVersion) {
}
