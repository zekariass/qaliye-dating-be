package com.qaliye.backend.chat.service;

import com.qaliye.backend.chat.exception.InvalidReceiptSequenceException;
import com.qaliye.backend.chat.repository.ChatMatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class ReceiptService {

    private final ChatMatchRepository matchRepository;
    private final MatchAuthorizationService authorizationService;
    private final ChatOutboxService outboxService;

    public ReceiptService(ChatMatchRepository matchRepository,
                          MatchAuthorizationService authorizationService,
                          ChatOutboxService outboxService) {
        this.matchRepository = matchRepository;
        this.authorizationService = authorizationService;
        this.outboxService = outboxService;
    }

    @Transactional
    public void markDelivered(UUID callerId, UUID matchId, long upToSequence) {
        authorizationService.requireActiveAccount(callerId);
        ChatMatchRepository.MatchRow match = matchRepository.findByIdForUpdate(matchId)
                .orElseThrow(com.qaliye.backend.chat.exception.MatchNotFoundException::new);
        if (!match.isParticipant(callerId))
            throw new com.qaliye.backend.chat.exception.MatchAccessDeniedException();
        if (!"ACTIVE".equals(match.status()))
            throw new com.qaliye.backend.chat.exception.MatchNotActiveException();

        long maxValid = match.nextMessageSequence() - 1;
        if (upToSequence < 0 || upToSequence > maxValid) {
            throw new InvalidReceiptSequenceException(
                    "upToSequence must be between 0 and " + maxValid + ".");
        }

        boolean isUserOne = match.isUserOne(callerId);
        long currentDelivered = isUserOne
                ? match.userOneLastDeliveredSequence()
                : match.userTwoLastDeliveredSequence();

        if (upToSequence <= currentDelivered) {
            return;
        }

        int changed = matchRepository.updateDeliveredSequence(matchId, isUserOne, upToSequence);
        if (changed > 0) {
            OffsetDateTime now = OffsetDateTime.now();
            long myReadSeq = isUserOne ? match.userOneLastReadSequence() : match.userTwoLastReadSequence();
            outboxService.createReceiptUpdatedEvent(matchId, callerId, upToSequence, myReadSeq, now);
        }
    }

    @Transactional
    public void markRead(UUID callerId, UUID matchId, long upToSequence) {
        authorizationService.requireActiveAccount(callerId);
        ChatMatchRepository.MatchRow match = matchRepository.findByIdForUpdate(matchId)
                .orElseThrow(com.qaliye.backend.chat.exception.MatchNotFoundException::new);
        if (!match.isParticipant(callerId))
            throw new com.qaliye.backend.chat.exception.MatchAccessDeniedException();
        if (!"ACTIVE".equals(match.status()))
            throw new com.qaliye.backend.chat.exception.MatchNotActiveException();

        long maxValid = match.nextMessageSequence() - 1;
        if (upToSequence < 0 || upToSequence > maxValid) {
            throw new InvalidReceiptSequenceException(
                    "upToSequence must be between 0 and " + maxValid + ".");
        }

        boolean isUserOne = match.isUserOne(callerId);
        long currentRead = isUserOne ? match.userOneLastReadSequence() : match.userTwoLastReadSequence();
        long currentDelivered = isUserOne
                ? match.userOneLastDeliveredSequence()
                : match.userTwoLastDeliveredSequence();

        if (upToSequence <= currentRead) {
            return;
        }

        long newDelivered = Math.max(currentDelivered, upToSequence);
        int changed = matchRepository.updateReadSequence(matchId, isUserOne, upToSequence, newDelivered);

        if (changed > 0) {
            OffsetDateTime now = OffsetDateTime.now();
            outboxService.createReceiptUpdatedEvent(matchId, callerId, newDelivered, upToSequence, now);
            outboxService.createInboxMatchUpdatedEvent(matchId, callerId, now);
        }
    }
}
