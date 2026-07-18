package com.qaliye.backend.chat.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SendMessageRequest {

    @NotNull
    @JsonAlias("clientMessageId")
    private UUID clientMessageId;

    @NotNull
    @JsonAlias("messageType")
    private String messageType;

    private String body;

    private List<Long> durations;

    public UUID getClientMessageId() { return clientMessageId; }
    public void setClientMessageId(UUID clientMessageId) { this.clientMessageId = clientMessageId; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<Long> getDurations() { return durations; }
    public void setDurations(List<Long> durations) { this.durations = durations; }
}
