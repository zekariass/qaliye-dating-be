package com.qaliye.backend.blinddate.service;

import com.qaliye.backend.blinddate.BlindDateConstants;
import com.qaliye.backend.blinddate.config.BlindDateProperties;
import com.qaliye.backend.blinddate.repository.BlindDateCatalogRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.ConfigurationRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.CustomQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.SetQuestionRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Manages the user's permanent Blind Date configuration and question set.
 * Everything here is free — paid actions only apply to session create/join.
 */
@Service
public class BlindDateQuestionSetService {

    public record LimitsView(int maxParticipants, int maxRounds, int maxRoundQuestions,
                             int maxQuestions, int maxCustomQuestions) {}

    public record ConfigurationView(boolean enabled, String languageCode,
                                    List<BlindDateCatalogRepository.LanguageRow> supportedLanguages,
                                    LimitsView limits) {}

    public record QuestionSetView(List<SetQuestionRow> questions,
                                  List<CustomQuestionRow> customQuestions) {}

    private final BlindDateQuestionSetRepository questionSetRepo;
    private final BlindDateCatalogRepository catalogRepo;
    private final BlindDateProperties properties;

    public BlindDateQuestionSetService(BlindDateQuestionSetRepository questionSetRepo,
                                       BlindDateCatalogRepository catalogRepo,
                                       BlindDateProperties properties) {
        this.questionSetRepo = questionSetRepo;
        this.catalogRepo = catalogRepo;
        this.properties = properties;
    }

    // ── Configuration ──────────────────────────────────────────────────────

    @Transactional
    public ConfigurationView getConfiguration(UUID userId) {
        ConfigurationRow config = questionSetRepo.ensureConfiguration(userId, "en");
        return new ConfigurationView(config.enabled(), config.languageCode(),
                catalogRepo.findActiveLanguages(), limits());
    }

    private LimitsView limits() {
        return new LimitsView(properties.getMaxParticipants(), properties.getMaxRounds(),
                properties.getMaxRoundQuestions(), properties.getMaxQuestions(),
                properties.getMaxCustomQuestions());
    }

    @Transactional
    public ConfigurationView updateConfiguration(UUID userId, String languageCode, Boolean enabled) {
        if (languageCode != null && !catalogRepo.isSupportedLanguage(languageCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_language");
        }
        questionSetRepo.ensureConfiguration(userId, languageCode != null ? languageCode : "en");
        questionSetRepo.updateConfiguration(userId, languageCode, enabled);
        return getConfiguration(userId);
    }

    // ── Question set ───────────────────────────────────────────────────────

    @Transactional
    public QuestionSetView getQuestionSet(UUID userId) {
        UUID setId = questionSetRepo.ensureQuestionSet(userId);
        String language = questionSetRepo.findConfiguration(userId)
                .map(ConfigurationRow::languageCode).orElse("en");
        return new QuestionSetView(
                questionSetRepo.findSetQuestions(setId, language, false),
                questionSetRepo.findCustomQuestions(setId, false));
    }

    @Transactional
    public SetQuestionRow addQuestion(UUID userId, UUID questionId, String answer) {
        if (!catalogRepo.isActiveQuestion(questionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "question_not_found");
        }
        validateAnswer(answer, false);
        UUID setId = questionSetRepo.ensureQuestionSet(userId);
        // Re-adding an already-active question is a no-op upsert, not a new slot.
        if (!questionSetRepo.isActiveSetQuestion(setId, questionId)
                && questionSetRepo.countActiveSetQuestions(setId) >= properties.getMaxQuestions()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "max_questions_reached");
        }
        UUID setQuestionId = questionSetRepo.addSetQuestion(setId, questionId,
                questionSetRepo.nextSortOrder(setId));
        if (answer != null) {
            questionSetRepo.upsertSetAnswer(setQuestionId, answer.trim());
        }
        String language = questionSetRepo.findConfiguration(userId)
                .map(ConfigurationRow::languageCode).orElse("en");
        return questionSetRepo.findSetQuestion(userId, setQuestionId, language)
                .orElseThrow(() -> new IllegalStateException("Set question missing after insert"));
    }

    @Transactional
    public void answerQuestion(UUID userId, UUID setQuestionId, String answer) {
        validateAnswer(answer, true);
        String language = questionSetRepo.findConfiguration(userId)
                .map(ConfigurationRow::languageCode).orElse("en");
        questionSetRepo.findSetQuestion(userId, setQuestionId, language)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "set_question_not_found"));
        questionSetRepo.upsertSetAnswer(setQuestionId, answer.trim());
    }

    @Transactional
    public void removeQuestion(UUID userId, UUID setQuestionId) {
        int deleted = questionSetRepo.deleteSetQuestion(userId, setQuestionId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "set_question_not_found");
        }
    }

    @Transactional
    public void reorderQuestion(UUID userId, UUID setQuestionId, int sortOrder) {
        validateSortOrder(sortOrder);
        int updated = questionSetRepo.reorderSetQuestion(userId, setQuestionId, sortOrder);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "set_question_not_found");
        }
    }

    // ── Custom questions ───────────────────────────────────────────────────

    @Transactional
    public CustomQuestionRow addCustomQuestion(UUID userId, String question, String answer) {
        validateCustomQuestion(question);
        validateAnswer(answer, true);
        UUID setId = questionSetRepo.ensureQuestionSet(userId);
        if (questionSetRepo.countActiveCustomQuestions(setId) >= properties.getMaxCustomQuestions()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "max_custom_questions_reached");
        }
        UUID id = questionSetRepo.addCustomQuestion(setId, question.trim(), answer.trim(),
                questionSetRepo.nextSortOrder(setId));
        return questionSetRepo.findCustomQuestion(userId, id)
                .orElseThrow(() -> new IllegalStateException("Custom question missing after insert"));
    }

    @Transactional
    public CustomQuestionRow updateCustomQuestion(UUID userId, UUID customQuestionId,
                                                  String question, String answer, Integer sortOrder) {
        if (question != null) validateCustomQuestion(question);
        if (answer != null) validateAnswer(answer, true);
        if (sortOrder != null) validateSortOrder(sortOrder);
        int updated = questionSetRepo.updateCustomQuestion(userId, customQuestionId,
                question != null ? question.trim() : null,
                answer != null ? answer.trim() : null,
                sortOrder);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "custom_question_not_found");
        }
        return questionSetRepo.findCustomQuestion(userId, customQuestionId)
                .orElseThrow(() -> new IllegalStateException("Custom question missing after update"));
    }

    @Transactional
    public void removeCustomQuestion(UUID userId, UUID customQuestionId) {
        int deleted = questionSetRepo.deleteCustomQuestion(userId, customQuestionId);
        if (deleted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "custom_question_not_found");
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────

    private void validateSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_sort_order");
        }
    }

    private void validateCustomQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question_required");
        }
        if (question.trim().length() > BlindDateConstants.MAX_CUSTOM_QUESTION_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question_too_long");
        }
    }

    private void validateAnswer(String answer, boolean required) {
        if (answer == null) {
            if (required) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer_required");
            }
            return;
        }
        if (answer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer_required");
        }
        if (answer.trim().length() > BlindDateConstants.MAX_ANSWER_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answer_too_long");
        }
    }
}
