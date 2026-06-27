package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.repository.ChatMatchRepository;
import com.qaliye.backend.chat.repository.ChatMatchRepository.MatchRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class MatchLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MatchLifecycleService.class);

    private final ChatMatchRepository matchRepository;
    private final ChatOutboxService chatOutboxService;

    public MatchLifecycleService(ChatMatchRepository matchRepository,
                                  ChatOutboxService chatOutboxService) {
        this.matchRepository = matchRepository;
        this.chatOutboxService = chatOutboxService;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean endMatch(UUID matchId, String endReason, UUID endedByUserId) {
        ChatMatchRepository.MatchRow match = matchRepository.findByIdForUpdate(matchId).orElse(null);
        if (match == null || !"ACTIVE".equals(match.status())) {
            log.debug("endMatch: match {} not found or already ended", matchId);
            return false;
        }

        Optional<UUID> ended = matchRepository.endMatch(matchId, endReason, endedByUserId);
        if (ended.isEmpty()) {
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();
        chatOutboxService.createMatchEndedEvent(matchId, endReason, now);
        chatOutboxService.createInboxMatchRemovedEvent(matchId, match.userOneId(), now);
        chatOutboxService.createInboxMatchRemovedEvent(matchId, match.userTwoId(), now);

        log.debug("endMatch: match {} ended with reason {}", matchId, endReason);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public boolean endMatchByPair(UUID userIdA, UUID userIdB, String endReason, UUID endedByUserId) {
        Optional<MatchRow> matchOpt = matchRepository.findActiveByPairForUpdate(userIdA, userIdB);
        if (matchOpt.isEmpty()) {
            return false;
        }
        return endMatch(matchOpt.get().id(), endReason, endedByUserId);
    }
}
