package com.qaliye.backend.auth.sms;

public interface SmsProvider {

    void send(String to, String message);
}
