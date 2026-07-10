package com.qaliye.backend.auth.hook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SendSmsHookPayload(
        User user,
        Sms sms
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(String phone) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sms(String otp) {}
}
