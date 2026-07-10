package com.qaliye.backend.auth.hook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.auth.sms.PhoneNormalizer;
import com.qaliye.backend.auth.sms.SmsDeliveryException;
import com.qaliye.backend.auth.sms.SmsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SendSmsHookService {

    private static final String SMS_TEMPLATE = "Your Qaliye verification code is %s. Do not share this code.";

    @Value("${supabase.auth.send-sms-hook-secret:}")
    private String hookSecret;

    private final SupabaseHookVerifier verifier;
    private final SmsProvider smsProvider;
    private final ObjectMapper objectMapper;

    public SendSmsHookService(SupabaseHookVerifier verifier,
                               SmsProvider smsProvider,
                               ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.smsProvider = smsProvider;
        this.objectMapper = objectMapper;
    }

    public void handle(byte[] rawBody,
                       String webhookId,
                       String webhookTimestamp,
                       String webhookSignature) {

        if (hookSecret == null || hookSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "hook_secret_not_configured");
        }

        if (!verifier.verify(rawBody, webhookId, webhookTimestamp, webhookSignature, hookSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_hook_signature");
        }

        SendSmsHookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, SendSmsHookPayload.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_payload");
        }

        if (payload.user() == null
                || payload.user().phone() == null
                || payload.user().phone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_phone");
        }

        if (payload.sms() == null
                || payload.sms().otp() == null
                || payload.sms().otp().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_otp");
        }

        String phone = PhoneNormalizer.normalizeEthiopian(payload.user().phone());

        String message = String.format(SMS_TEMPLATE, payload.sms().otp());

        try {
            smsProvider.send(phone, message);
        } catch (SmsDeliveryException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "sms_delivery_failed");
        }
    }
}
