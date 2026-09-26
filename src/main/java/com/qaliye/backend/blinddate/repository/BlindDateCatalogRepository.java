package com.qaliye.backend.blinddate.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read access to the platform-owned Blind Date catalog: supported languages,
 * question categories and questions. Text is resolved through the normalized
 * translation tables with a fallback to English and finally the untranslated
 * base name.
 */
@Repository
public class BlindDateCatalogRepository {

    public record LanguageRow(String code, String name, int sortOrder) {}

    public record CategoryRow(UUID id, String code, String name, String description,
                              String iconUrl, int sortOrder) {}

    public record QuestionRow(UUID id, UUID categoryId, String code, String question, int sortOrder) {}

    private static final String FIND_ACTIVE_LANGUAGES_SQL = """
            SELECT code, name, sort_order
            FROM blind_date_languages
            WHERE active = TRUE
            ORDER BY sort_order, code
            """;

    private static final String LANGUAGE_EXISTS_SQL = """
            SELECT COUNT(1) FROM blind_date_languages
            WHERE code = :code AND active = TRUE
            """;

    private static final String FIND_CATEGORIES_SQL = """
            SELECT c.id,
                   c.code,
                   COALESCE(t.name, en.name, c.name)                 AS name,
                   COALESCE(t.description, en.description, c.description) AS description,
                   c.icon_url,
                   c.sort_order
            FROM blind_date_question_categories c
            LEFT JOIN blind_date_question_category_translations t
                   ON t.category_id = c.id AND t.language_code = :languageCode
            LEFT JOIN blind_date_question_category_translations en
                   ON en.category_id = c.id AND en.language_code = 'en'
            WHERE c.active = TRUE
            ORDER BY c.sort_order, c.code
            """;

    private static final String FIND_QUESTIONS_BY_CATEGORY_SQL = """
            SELECT q.id,
                   q.category_id,
                   q.code,
                   COALESCE(t.question, en.question, q.code) AS question,
                   q.sort_order
            FROM blind_date_questions q
            LEFT JOIN blind_date_question_translations t
                   ON t.question_id = q.id AND t.language_code = :languageCode
            LEFT JOIN blind_date_question_translations en
                   ON en.question_id = q.id AND en.language_code = 'en'
            WHERE q.active = TRUE
              AND (CAST(:categoryId AS uuid) IS NULL OR q.category_id = CAST(:categoryId AS uuid))
            ORDER BY q.sort_order, q.code
            """;

    /** Resolves the display text for a single platform question in a language. */
    private static final String FIND_QUESTION_TEXT_SQL = """
            SELECT COALESCE(t.question, en.question, q.code) AS question
            FROM blind_date_questions q
            LEFT JOIN blind_date_question_translations t
                   ON t.question_id = q.id AND t.language_code = :languageCode
            LEFT JOIN blind_date_question_translations en
                   ON en.question_id = q.id AND en.language_code = 'en'
            WHERE q.id = :questionId
            """;

    private static final String QUESTION_IS_ACTIVE_SQL = """
            SELECT COUNT(1) FROM blind_date_questions
            WHERE id = :questionId AND active = TRUE
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BlindDateCatalogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LanguageRow> findActiveLanguages() {
        return jdbc.query(FIND_ACTIVE_LANGUAGES_SQL, new MapSqlParameterSource(),
                (rs, i) -> new LanguageRow(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getInt("sort_order")));
    }

    public boolean isSupportedLanguage(String code) {
        if (code == null || code.isBlank()) return false;
        Integer count = jdbc.queryForObject(LANGUAGE_EXISTS_SQL,
                new MapSqlParameterSource("code", code), Integer.class);
        return count != null && count > 0;
    }

    public List<CategoryRow> findCategories(String languageCode) {
        return jdbc.query(FIND_CATEGORIES_SQL,
                new MapSqlParameterSource("languageCode", languageCode),
                (rs, i) -> new CategoryRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getString("icon_url"),
                        rs.getInt("sort_order")));
    }

    public List<QuestionRow> findQuestions(UUID categoryId, String languageCode) {
        var params = new MapSqlParameterSource()
                .addValue("categoryId", categoryId)
                .addValue("languageCode", languageCode);
        return jdbc.query(FIND_QUESTIONS_BY_CATEGORY_SQL, params,
                (rs, i) -> new QuestionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("code"),
                        rs.getString("question"),
                        rs.getInt("sort_order")));
    }

    public Optional<String> findQuestionText(UUID questionId, String languageCode) {
        var params = new MapSqlParameterSource()
                .addValue("questionId", questionId)
                .addValue("languageCode", languageCode);
        return jdbc.query(FIND_QUESTION_TEXT_SQL, params, (rs, i) -> rs.getString("question"))
                .stream().findFirst();
    }

    public boolean isActiveQuestion(UUID questionId) {
        Integer count = jdbc.queryForObject(QUESTION_IS_ACTIVE_SQL,
                new MapSqlParameterSource("questionId", questionId), Integer.class);
        return count != null && count > 0;
    }
}
