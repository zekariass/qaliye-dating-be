package com.qaliye.backend.auth.hook;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/supabase")
public class SendSmsHookController {

    private final SendSmsHookService hookService;

    public SendSmsHookController(SendSmsHookService hookService) {
        this.hookService = hookService;
    }

    @PostMapping(value = "/send-sms-hook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleSendSmsHook(@RequestBody byte[] rawBody,
                                                   HttpServletRequest request) {
        hookService.handle(
                rawBody,
                request.getHeader("webhook-id"),
                request.getHeader("webhook-timestamp"),
                request.getHeader("webhook-signature")
        );
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{}");
    }
}
