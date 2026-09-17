package com.micon.backend.voice.application;

public record VoiceProfileSavedEvent(long userId, long profileId, long profileVersion) {
}
