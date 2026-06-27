package com.qaliye.backend.chat.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class SendMessageRequest {

    @NotNull
    private UUID clientMessageId;

    @NotNull
    private String messageType;

    private String body;

    public UUID getClientMessageId() { return clientMessageId; }
    public void setClientMessageId(UUID clientMessageId) { this.clientMessageId = clientMessageId; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
}
