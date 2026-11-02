package com.officechat.office_chat_backend.message;

public enum MessageType {
    TEXT,
    IMAGE,
    FILE,
    VOICE,
    AUDIO,
    VIDEO,
    CALL,       // call log entry posted by the server ("Missed voice call", "Video call · 4m")
    POLL
}