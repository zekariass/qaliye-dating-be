package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.config.BlindDateProperties;
import com.qaliye.backend.blinddate.repository.BlindDateCatalogRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.ConfigurationRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.CustomQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.SetQuestionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlindDateQuestionSetServiceTest {

    @Mock BlindDateQuestionSetRepository questionSetRepo;
    @Mock BlindDateCatalogRepository catalogRepo;

    BlindDateQuestionSetService service;
    BlindDateProperties properties;

    UUID userId = UUID.randomUUID();
    UUID setId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new BlindDateProperties();
        properties.setMaxQuestions(3);
        properties.setMaxCustomQuestions(2);
        service = new BlindDateQuestionSetService(questionSetRepo, catalogRepo, properties);
    }

    // ── Platform question limit ────────────────────────────────────────────

    @Test
    void addQuestion_underLimit_addsQuestion() {
        UUID setQuestionId = UUID.randomUUID();
        when(catalogRepo.isActiveQuestion(questionId)).thenReturn(true);
        when(questionSetRepo.ensureQuestionSet(userId)).thenReturn(setId);
        when(questionSetRepo.isActiveSetQuestion(setId, questionId)).thenReturn(false);
        when(questionSetRepo.countActiveSetQuestions(setId)).thenReturn(2);
        when(questionSetRepo.nextSortOrder(setId)).thenReturn(3);
        when(questionSetRepo.addSetQuestion(setId, questionId, 3)).thenReturn(setQuestionId);
        when(questionSetRepo.findConfiguration(userId))
                .thenReturn(Optional.of(new ConfigurationRow(UUID.randomUUID(), userId, true, "en")));
        when(questionSetRepo.findSetQuestion(userId, setQuestionId, "en"))
                .thenReturn(Optional.of(new SetQuestionRow(setQuestionId, questionId,
                        "Q?", "A.", 3, true, UUID.randomUUID(), "LIFESTYLE")));

        SetQuestionRow row = service.addQuestion(userId, questionId, "A.");

        assertThat(row.id()).isEqualTo(setQuestionId);
        verify(questionSetRepo).upsertSetAnswer(setQuestionId, "A.");
    }

    @Test
    void addQuestion_atLimit_throwsConflict() {
        when(catalogRepo.isActiveQuestion(questionId)).thenReturn(true);
        when(questionSetRepo.ensureQuestionSet(userId)).thenReturn(setId);
        when(questionSetRepo.isActiveSetQuestion(setId, questionId)).thenReturn(false);
        when(questionSetRepo.countActiveSetQuestions(setId)).thenReturn(3);

        assertThatThrownBy(() -> service.addQuestion(userId, questionId, "A."))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("max_questions_reached");
                });
        verify(questionSetRepo, never()).addSetQuestion(any(), any(), anyInt());
    }

    @Test
    void addQuestion_reAddingActiveQuestion_atLimit_stillAllowed() {
        UUID setQuestionId = UUID.randomUUID();
        when(catalogRepo.isActiveQuestion(questionId)).thenReturn(true);
        when(questionSetRepo.ensureQuestionSet(userId)).thenReturn(setId);
        when(questionSetRepo.isActiveSetQuestion(setId, questionId)).thenReturn(true);
        when(questionSetRepo.nextSortOrder(setId)).thenReturn(4);
        when(questionSetRepo.addSetQuestion(setId, questionId, 4)).thenReturn(setQuestionId);
        when(questionSetRepo.findConfiguration(userId))
                .thenReturn(Optional.of(new ConfigurationRow(UUID.randomUUID(), userId, true, "en")));
        when(questionSetRepo.findSetQuestion(userId, setQuestionId, "en"))
                .thenReturn(Optional.of(new SetQuestionRow(setQuestionId, questionId,
                        "Q?", null, 4, true, UUID.randomUUID(), "LIFESTYLE")));

        SetQuestionRow row = service.addQuestion(userId, questionId, null);

        assertThat(row.id()).isEqualTo(setQuestionId);
        verify(questionSetRepo, never()).countActiveSetQuestions(any());
    }

    // ── Custom question limit ──────────────────────────────────────────────

    @Test
    void addCustomQuestion_underLimit_addsQuestion() {
        UUID customId = UUID.randomUUID();
        when(questionSetRepo.ensureQuestionSet(userId)).thenReturn(setId);
        when(questionSetRepo.countActiveCustomQuestions(setId)).thenReturn(1);
        when(questionSetRepo.nextSortOrder(setId)).thenReturn(2);
        when(questionSetRepo.addCustomQuestion(setId, "Coffee or tea?", "Coffee.", 2))
                .thenReturn(customId);
        when(questionSetRepo.findCustomQuestion(userId, customId))
                .thenReturn(Optional.of(new CustomQuestionRow(customId, "Coffee or tea?", "Coffee.", 2, true)));

        CustomQuestionRow row = service.addCustomQuestion(userId, "Coffee or tea?", "Coffee.");

        assertThat(row.id()).isEqualTo(customId);
    }

    @Test
    void addCustomQuestion_atLimit_throwsConflict() {
        when(questionSetRepo.ensureQuestionSet(userId)).thenReturn(setId);
        when(questionSetRepo.countActiveCustomQuestions(setId)).thenReturn(2);

        assertThatThrownBy(() -> service.addCustomQuestion(userId, "Coffee or tea?", "Coffee."))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("max_custom_questions_reached");
                });
        verify(questionSetRepo, never()).addCustomQuestion(any(), any(), any(), anyInt());
    }
}
