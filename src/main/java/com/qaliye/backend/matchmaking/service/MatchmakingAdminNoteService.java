package com.qaliye.backend.matchmaking.service;

import com.qaliye.backend.matchmaking.repository.MatchmakingAdminNoteRepository;
import com.qaliye.backend.matchmaking.repository.MatchmakingAdminNoteRepository.NoteRow;
import com.qaliye.backend.matchmaking.repository.MatchmakingRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only internal notes on a matchmaking request.
 */
@Service
public class MatchmakingAdminNoteService {

    private final MatchmakingAdminNoteRepository noteRepo;
    private final MatchmakingRequestRepository requestRepo;

    public MatchmakingAdminNoteService(MatchmakingAdminNoteRepository noteRepo,
                                       MatchmakingRequestRepository requestRepo) {
        this.noteRepo = noteRepo;
        this.requestRepo = requestRepo;
    }

    @Transactional
    public NoteRow addNote(UUID requestId, UUID adminUserId, String note) {
        requestRepo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Request not found."));
        UUID noteId = noteRepo.insert(requestId, adminUserId, note);
        return noteRepo.findByRequestId(requestId).stream()
                .filter(n -> n.id().equals(noteId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Note not found after insert"));
    }

    @Transactional(readOnly = true)
    public List<NoteRow> getNotes(UUID requestId) {
        requestRepo.findById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Request not found."));
        return noteRepo.findByRequestId(requestId);
    }
}
