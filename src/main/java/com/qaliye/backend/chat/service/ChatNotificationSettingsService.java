package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.repository.ChatNotificationSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class ChatNotificationSettingsService {

    private final ChatNotificationSettingsRepository settingsRepo;
    private final MatchAuthorizationService authorizationService;

    public ChatNotificationSettingsService(ChatNotificationSettingsRepository settingsRepo,
                                           MatchAuthorizationService authorizationService) {
        this.settingsRepo = settingsRepo;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public void updateMuteSetting(UUID callerId, UUID matchId, Instant mutedUntil) {
        authorizationService.authorize(callerId, matchId);
        OffsetDateTime mutedUntilOdt = mutedUntil != null
                ? mutedUntil.atOffset(ZoneOffset.UTC) : null;
        settingsRepo.upsert(matchId, callerId, mutedUntilOdt);
    }
}
