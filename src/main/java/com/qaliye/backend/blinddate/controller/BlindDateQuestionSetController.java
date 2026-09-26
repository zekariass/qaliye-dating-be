package com.qaliye.backend.blinddate.controller;

import com.qaliye.backend.blinddate.repository.BlindDateCatalogRepository;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.CustomQuestionRow;
import com.qaliye.backend.blinddate.repository.BlindDateQuestionSetRepository.SetQuestionRow;
import com.qaliye.backend.blinddate.service.BlindDateQuestionSetService;
import com.qaliye.backend.blinddate.service.BlindDateQuestionSetService.ConfigurationView;
import com.qaliye.backend.blinddate.service.BlindDateQuestionSetService.QuestionSetView;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/blind-date")
public class BlindDateQuestionSetController {

    private final BlindDateQuestionSetService questionSetService;
    private final BlindDateCatalogRepository catalogRepo;

    public BlindDateQuestionSetController(BlindDateQuestionSetService questionSetService,
                                          BlindDateCatalogRepository catalogRepo) {
        this.questionSetService = questionSetService;
        this.catalogRepo = catalogRepo;
    }

    // ── Catalog ────────────────────────────────────────────────────────────

    @GetMapping("/catalog/categories")
    public ResponseEntity<List<Map<String, Object>>> categories(
            @RequestParam(defaultValue = "en") String language) {
        List<Map<String, Object>> body = catalogRepo.findCategories(language).stream()
                .map(this::categoryToMap).toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/catalog/questions")
    public ResponseEntity<List<Map<String, Object>>> questions(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "en") String language) {
        List<Map<String, Object>> body = catalogRepo.findQuestions(categoryId, language).stream()
                .map(this::questionToMap).toList();
        return ResponseEntity.ok(body);
    }

    // ── Configuration ──────────────────────────────────────────────────────

    @GetMapping("/configuration")
    public ResponseEntity<Map<String, Object>> getConfiguration() {
        ConfigurationView view = questionSetService.getConfiguration(CallerUtils.callerId());
        return ResponseEntity.ok(configurationToMap(view));
    }

    @PatchMapping("/configuration")
    public ResponseEntity<Map<String, Object>> updateConfiguration(
            @RequestBody UpdateConfigurationRequest request) {
        ConfigurationView view = questionSetService.updateConfiguration(
                CallerUtils.callerId(), request.languageCode(), request.enabled());
        return ResponseEntity.ok(configurationToMap(view));
    }

    // ── Question set ───────────────────────────────────────────────────────

    @GetMapping("/question-set")
    public ResponseEntity<Map<String, Object>> getQuestionSet() {
        QuestionSetView view = questionSetService.getQuestionSet(CallerUtils.callerId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("questions", view.questions().stream().map(this::setQuestionToMap).toList());
        body.put("custom_questions", view.customQuestions().stream().map(this::customToMap).toList());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/question-set/questions")
    public ResponseEntity<Map<String, Object>> addQuestion(
            @Valid @RequestBody AddQuestionRequest request) {
        SetQuestionRow row = questionSetService.addQuestion(
                CallerUtils.callerId(), request.questionId(), request.answer());
        return ResponseEntity.ok(setQuestionToMap(row));
    }

    @PostMapping("/question-set/questions/{setQuestionId}/answer")
    public ResponseEntity<Void> answerQuestion(@PathVariable UUID setQuestionId,
                                               @Valid @RequestBody AnswerRequest request) {
        questionSetService.answerQuestion(CallerUtils.callerId(), setQuestionId, request.answer());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/question-set/questions/{setQuestionId}")
    public ResponseEntity<Void> removeQuestion(@PathVariable UUID setQuestionId) {
        questionSetService.removeQuestion(CallerUtils.callerId(), setQuestionId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/question-set/questions/{setQuestionId}/order")
    public ResponseEntity<Void> reorderQuestion(@PathVariable UUID setQuestionId,
                                                @Valid @RequestBody ReorderRequest request) {
        questionSetService.reorderQuestion(CallerUtils.callerId(), setQuestionId, request.sortOrder());
        return ResponseEntity.noContent().build();
    }

    // ── Custom questions ───────────────────────────────────────────────────

    @PostMapping("/question-set/custom-questions")
    public ResponseEntity<Map<String, Object>> addCustomQuestion(
            @Valid @RequestBody CustomQuestionRequest request) {
        CustomQuestionRow row = questionSetService.addCustomQuestion(
                CallerUtils.callerId(), request.question(), request.answer());
        return ResponseEntity.ok(customToMap(row));
    }

    @PatchMapping("/question-set/custom-questions/{customQuestionId}")
    public ResponseEntity<Map<String, Object>> updateCustomQuestion(
            @PathVariable UUID customQuestionId,
            @RequestBody UpdateCustomQuestionRequest request) {
        CustomQuestionRow row = questionSetService.updateCustomQuestion(
                CallerUtils.callerId(), customQuestionId,
                request.question(), request.answer(), request.sortOrder());
        return ResponseEntity.ok(customToMap(row));
    }

    @DeleteMapping("/question-set/custom-questions/{customQuestionId}")
    public ResponseEntity<Void> removeCustomQuestion(@PathVariable UUID customQuestionId) {
        questionSetService.removeCustomQuestion(CallerUtils.callerId(), customQuestionId);
        return ResponseEntity.noContent().build();
    }

    // ── Requests ───────────────────────────────────────────────────────────

    public record UpdateConfigurationRequest(String languageCode, Boolean enabled) {}

    public record AddQuestionRequest(@NotNull UUID questionId, String answer) {}

    public record AnswerRequest(@NotBlank String answer) {}

    public record ReorderRequest(@NotNull Integer sortOrder) {}

    public record CustomQuestionRequest(@NotBlank String question, @NotBlank String answer) {}

    public record UpdateCustomQuestionRequest(String question, String answer, Integer sortOrder) {}

    // ── Mappers ────────────────────────────────────────────────────────────

    private Map<String, Object> categoryToMap(BlindDateCatalogRepository.CategoryRow c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("code", c.code());
        m.put("name", c.name());
        m.put("description", c.description());
        m.put("icon_url", c.iconUrl());
        m.put("sort_order", c.sortOrder());
        return m;
    }

    private Map<String, Object> questionToMap(BlindDateCatalogRepository.QuestionRow q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id());
        m.put("category_id", q.categoryId());
        m.put("code", q.code());
        m.put("question", q.question());
        m.put("sort_order", q.sortOrder());
        return m;
    }

    private Map<String, Object> configurationToMap(ConfigurationView view) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", view.enabled());
        m.put("language_code", view.languageCode());
        m.put("supported_languages", view.supportedLanguages().stream().map(l -> {
            Map<String, Object> lm = new LinkedHashMap<String, Object>();
            lm.put("code", l.code());
            lm.put("name", l.name());
            return lm;
        }).toList());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_participants", view.limits().maxParticipants());
        limits.put("max_rounds", view.limits().maxRounds());
        limits.put("max_round_questions", view.limits().maxRoundQuestions());
        limits.put("max_questions", view.limits().maxQuestions());
        limits.put("max_custom_questions", view.limits().maxCustomQuestions());
        m.put("limits", limits);
        return m;
    }

    private Map<String, Object> setQuestionToMap(SetQuestionRow q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id());
        m.put("question_id", q.questionId());
        m.put("question", q.questionText());
        m.put("answer", q.answer());
        m.put("sort_order", q.sortOrder());
        m.put("category_id", q.categoryId());
        m.put("category_code", q.categoryCode());
        return m;
    }

    private Map<String, Object> customToMap(CustomQuestionRow q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id());
        m.put("question", q.question());
        m.put("answer", q.answer());
        m.put("sort_order", q.sortOrder());
        return m;
    }
}
