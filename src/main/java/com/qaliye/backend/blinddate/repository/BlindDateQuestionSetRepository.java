package com.qaliye.backend.blinddate.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The user's permanent Blind Date configuration and question set: selected
 * platform questions with answers, plus custom questions. Nothing here is a paid
 * action and the data is freely editable — session snapshots are taken elsewhere
 * and are unaffected by later edits.
 */
@Repository
public class BlindDateQuestionSetRepository {

    public record ConfigurationRow(UUID id, UUID userId, boolean enabled, String languageCode) {}

    /** A platform question selected into the user's set, with its resolved text and answer. */
    public record SetQuestionRow(UUID id, UUID questionId, String questionText,
                                 String answer, int sortOrder, boolean active,
                                 UUID categoryId, String categoryCode) {}

    public record CustomQuestionRow(UUID id, String question, String answer,
                                    int sortOrder, boolean active) {}

    // ── Configuration ──────────────────────────────────────────────────────

    private static final String UPSERT_CONFIGURATION_SQL = """
            INSERT INTO blind_date_configurations (user_id, enabled, language_code)
            VALUES (:userId, TRUE, :languageCode)
            ON CONFLICT (user_id) DO NOTHING
            """;

    private static final String FIND_CONFIGURATION_SQL = """
            SELECT id, user_id, enabled, language_code
            FROM blind_date_configurations
            WHERE user_id = :userId
            """;

    private static final String UPDATE_CONFIGURATION_SQL = """
            UPDATE blind_date_configurations
            SET language_code = COALESCE(:languageCode, language_code),
                enabled       = COALESCE(:enabled, enabled),
                updated_at    = NOW()
            WHERE user_id = :userId
            """;

    // ── Question set ───────────────────────────────────────────────────────

    private static final String ENSURE_QUESTION_SET_SQL = """
            INSERT INTO blind_date_question_sets (user_id)
            VALUES (:userId)
            ON CONFLICT (user_id) DO NOTHING
            """;

    private static final String FIND_QUESTION_SET_ID_SQL = """
            SELECT id FROM blind_date_question_sets WHERE user_id = :userId
            """;

    private static final String INSERT_SET_QUESTION_SQL = """
            INSERT INTO blind_date_question_set_questions
                (question_set_id, question_id, sort_order, active)
            VALUES (:questionSetId, :questionId, :sortOrder, TRUE)
            ON CONFLICT (question_set_id, question_id) DO UPDATE
                SET active     = TRUE,
                    sort_order = EXCLUDED.sort_order,
                    updated_at = NOW()
            RETURNING id
            """;

    private static final String UPSERT_SET_ANSWER_SQL = """
            INSERT INTO blind_date_question_set_answers (question_set_question_id, answer)
            VALUES (:questionSetQuestionId, :answer)
            ON CONFLICT (question_set_question_id) DO UPDATE
                SET answer     = EXCLUDED.answer,
                    updated_at = NOW()
            """;

    private static final String FIND_SET_QUESTIONS_SQL = """
            SELECT sq.id,
                   sq.question_id,
                   COALESCE(t.question, en.question, q.code) AS question_text,
                   a.answer,
                   sq.sort_order,
                   sq.active,
                   q.category_id,
                   c.code AS category_code
            FROM blind_date_question_set_questions sq
            JOIN blind_date_questions q ON q.id = sq.question_id
            JOIN blind_date_question_categories c ON c.id = q.category_id
            LEFT JOIN blind_date_question_set_answers a ON a.question_set_question_id = sq.id
            LEFT JOIN blind_date_question_translations t
                   ON t.question_id = q.id AND t.language_code = :languageCode
            LEFT JOIN blind_date_question_translations en
                   ON en.question_id = q.id AND en.language_code = 'en'
            WHERE sq.question_set_id = :questionSetId
              AND (:includeInactive = TRUE OR sq.active = TRUE)
            ORDER BY sq.sort_order, sq.created_at
            """;

    private static final String FIND_SET_QUESTION_BY_ID_SQL = """
            SELECT sq.id,
                   sq.question_id,
                   COALESCE(t.question, en.question, q.code) AS question_text,
                   a.answer,
                   sq.sort_order,
                   sq.active,
                   q.category_id,
                   c.code AS category_code
            FROM blind_date_question_set_questions sq
            JOIN blind_date_question_sets s ON s.id = sq.question_set_id
            JOIN blind_date_questions q ON q.id = sq.question_id
            JOIN blind_date_question_categories c ON c.id = q.category_id
            LEFT JOIN blind_date_question_set_answers a ON a.question_set_question_id = sq.id
            LEFT JOIN blind_date_question_translations t
                   ON t.question_id = q.id AND t.language_code = :languageCode
            LEFT JOIN blind_date_question_translations en
                   ON en.question_id = q.id AND en.language_code = 'en'
            WHERE sq.id = :setQuestionId
              AND s.user_id = :userId
            """;

    private static final String DELETE_SET_QUESTION_SQL = """
            DELETE FROM blind_date_question_set_questions sq
            USING blind_date_question_sets s
            WHERE sq.question_set_id = s.id
              AND sq.id = :setQuestionId
              AND s.user_id = :userId
            """;

    private static final String REORDER_SET_QUESTION_SQL = """
            UPDATE blind_date_question_set_questions sq
            SET sort_order = :sortOrder,
                updated_at = NOW()
            FROM blind_date_question_sets s
            WHERE sq.question_set_id = s.id
              AND sq.id = :setQuestionId
              AND s.user_id = :userId
            """;

    // ── Custom questions ───────────────────────────────────────────────────

    private static final String INSERT_CUSTOM_QUESTION_SQL = """
            INSERT INTO blind_date_custom_questions
                (question_set_id, question, answer, sort_order, active)
            VALUES (:questionSetId, :question, :answer, :sortOrder, TRUE)
            RETURNING id
            """;

    private static final String UPDATE_CUSTOM_QUESTION_SQL = """
            UPDATE blind_date_custom_questions cq
            SET question   = COALESCE(:question, cq.question),
                answer     = COALESCE(:answer, cq.answer),
                sort_order = COALESCE(:sortOrder, cq.sort_order),
                updated_at = NOW()
            FROM blind_date_question_sets s
            WHERE cq.question_set_id = s.id
              AND cq.id = :customQuestionId
              AND s.user_id = :userId
            """;

    private static final String DELETE_CUSTOM_QUESTION_SQL = """
            DELETE FROM blind_date_custom_questions cq
            USING blind_date_question_sets s
            WHERE cq.question_set_id = s.id
              AND cq.id = :customQuestionId
              AND s.user_id = :userId
            """;

    private static final String FIND_CUSTOM_QUESTIONS_SQL = """
            SELECT id, question, answer, sort_order, active
            FROM blind_date_custom_questions
            WHERE question_set_id = :questionSetId
              AND (:includeInactive = TRUE OR active = TRUE)
            ORDER BY sort_order, created_at
            """;

    private static final String FIND_CUSTOM_QUESTION_BY_ID_SQL = """
            SELECT cq.id, cq.question, cq.answer, cq.sort_order, cq.active
            FROM blind_date_custom_questions cq
            JOIN blind_date_question_sets s ON s.id = cq.question_set_id
            WHERE cq.id = :customQuestionId
              AND s.user_id = :userId
            """;

    /** Admin moderation: deactivate a reported custom question. */
    private static final String SET_CUSTOM_QUESTION_ACTIVE_SQL = """
            UPDATE blind_date_custom_questions
            SET active = :active, updated_at = NOW()
            WHERE id = :customQuestionId
            """;

    private static final String COUNT_ACTIVE_SET_QUESTIONS_SQL = """
            SELECT COUNT(1) FROM blind_date_question_set_questions
            WHERE question_set_id = :questionSetId AND active = TRUE
            """;

    private static final String EXISTS_ACTIVE_SET_QUESTION_SQL = """
            SELECT EXISTS(
                SELECT 1 FROM blind_date_question_set_questions
                WHERE question_set_id = :questionSetId
                  AND question_id = :questionId
                  AND active = TRUE)
            """;

    private static final String COUNT_ACTIVE_CUSTOM_QUESTIONS_SQL = """
            SELECT COUNT(1) FROM blind_date_custom_questions
            WHERE question_set_id = :questionSetId AND active = TRUE
            """;

    private static final String NEXT_SORT_ORDER_SQL = """
            SELECT COALESCE(MAX(sort_order), 0) + 1 FROM (
                SELECT sort_order FROM blind_date_question_set_questions
                WHERE question_set_id = :questionSetId
                UNION ALL
                SELECT sort_order FROM blind_date_custom_questions
                WHERE question_set_id = :questionSetId
            ) all_items
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BlindDateQuestionSetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Configuration ──────────────────────────────────────────────────────

    public ConfigurationRow ensureConfiguration(UUID userId, String defaultLanguageCode) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("languageCode", defaultLanguageCode);
        jdbc.update(UPSERT_CONFIGURATION_SQL, params);
        return findConfiguration(userId).orElseThrow(
                () -> new IllegalStateException("Blind Date configuration missing after upsert"));
    }

    public Optional<ConfigurationRow> findConfiguration(UUID userId) {
        return jdbc.query(FIND_CONFIGURATION_SQL, new MapSqlParameterSource("userId", userId),
                        (rs, i) -> new ConfigurationRow(
                                rs.getObject("id", UUID.class),
                                rs.getObject("user_id", UUID.class),
                                rs.getBoolean("enabled"),
                                rs.getString("language_code")))
                .stream().findFirst();
    }

    public int updateConfiguration(UUID userId, String languageCode, Boolean enabled) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("languageCode", languageCode)
                .addValue("enabled", enabled);
        return jdbc.update(UPDATE_CONFIGURATION_SQL, params);
    }

    // ── Question set ───────────────────────────────────────────────────────

    public UUID ensureQuestionSet(UUID userId) {
        var params = new MapSqlParameterSource("userId", userId);
        jdbc.update(ENSURE_QUESTION_SET_SQL, params);
        return findQuestionSetId(userId).orElseThrow(
                () -> new IllegalStateException("Blind Date question set missing after upsert"));
    }

    public Optional<UUID> findQuestionSetId(UUID userId) {
        return jdbc.query(FIND_QUESTION_SET_ID_SQL, new MapSqlParameterSource("userId", userId),
                        (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst();
    }

    public int countActiveSetQuestions(UUID questionSetId) {
        Integer count = jdbc.queryForObject(COUNT_ACTIVE_SET_QUESTIONS_SQL,
                new MapSqlParameterSource("questionSetId", questionSetId), Integer.class);
        return count != null ? count : 0;
    }

    public boolean isActiveSetQuestion(UUID questionSetId, UUID questionId) {
        var params = new MapSqlParameterSource()
                .addValue("questionSetId", questionSetId)
                .addValue("questionId", questionId);
        Boolean exists = jdbc.queryForObject(EXISTS_ACTIVE_SET_QUESTION_SQL, params, Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public int countActiveCustomQuestions(UUID questionSetId) {
        Integer count = jdbc.queryForObject(COUNT_ACTIVE_CUSTOM_QUESTIONS_SQL,
                new MapSqlParameterSource("questionSetId", questionSetId), Integer.class);
        return count != null ? count : 0;
    }

    public int nextSortOrder(UUID questionSetId) {
        Integer next = jdbc.queryForObject(NEXT_SORT_ORDER_SQL,
                new MapSqlParameterSource("questionSetId", questionSetId), Integer.class);
        return next != null ? next : 1;
    }

    public UUID addSetQuestion(UUID questionSetId, UUID questionId, int sortOrder) {
        var params = new MapSqlParameterSource()
                .addValue("questionSetId", questionSetId)
                .addValue("questionId", questionId)
                .addValue("sortOrder", sortOrder);
        return jdbc.query(INSERT_SET_QUESTION_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to add Blind Date question"));
    }

    public void upsertSetAnswer(UUID questionSetQuestionId, String answer) {
        var params = new MapSqlParameterSource()
                .addValue("questionSetQuestionId", questionSetQuestionId)
                .addValue("answer", answer);
        jdbc.update(UPSERT_SET_ANSWER_SQL, params);
    }

    public List<SetQuestionRow> findSetQuestions(UUID questionSetId, String languageCode,
                                                 boolean includeInactive) {
        var params = new MapSqlParameterSource()
                .addValue("questionSetId", questionSetId)
                .addValue("languageCode", languageCode)
                .addValue("includeInactive", includeInactive);
        return jdbc.query(FIND_SET_QUESTIONS_SQL, params, this::mapSetQuestion);
    }

    public Optional<SetQuestionRow> findSetQuestion(UUID userId, UUID setQuestionId, String languageCode) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("setQuestionId", setQuestionId)
                .addValue("languageCode", languageCode);
        return jdbc.query(FIND_SET_QUESTION_BY_ID_SQL, params, this::mapSetQuestion)
                .stream().findFirst();
    }

    public int deleteSetQuestion(UUID userId, UUID setQuestionId) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("setQuestionId", setQuestionId);
        return jdbc.update(DELETE_SET_QUESTION_SQL, params);
    }

    public int reorderSetQuestion(UUID userId, UUID setQuestionId, int sortOrder) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("setQuestionId", setQuestionId)
                .addValue("sortOrder", sortOrder);
        return jdbc.update(REORDER_SET_QUESTION_SQL, params);
    }

    // ── Custom questions ───────────────────────────────────────────────────

    public UUID addCustomQuestion(UUID questionSetId, String question, String answer, int sortOrder) {
        var params = new MapSqlParameterSource()
                .addValue("questionSetId", questionSetId)
                .addValue("question", question)
                .addValue("answer", answer)
                .addValue("sortOrder", sortOrder);
        return jdbc.query(INSERT_CUSTOM_QUESTION_SQL, params, (rs, i) -> rs.getObject("id", UUID.class))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to add custom Blind Date question"));
    }

    public int updateCustomQuestion(UUID userId, UUID customQuestionId, String question,
                                    String answer, Integer sortOrder) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("customQuestionId", customQuestionId)
                .addValue("question", question)
                .addValue("answer", answer)
                .addValue("sortOrder", sortOrder);
        return jdbc.update(UPDATE_CUSTOM_QUESTION_SQL, params);
    }

    public int deleteCustomQuestion(UUID userId, UUID customQuestionId) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("customQuestionId", customQuestionId);
        return jdbc.update(DELETE_CUSTOM_QUESTION_SQL, params);
    }

    public List<CustomQuestionRow> findCustomQuestions(UUID questionSetId, boolean includeInactive) {
        var params = new MapSqlParameterSource()
                .addValue("questionSetId", questionSetId)
                .addValue("includeInactive", includeInactive);
        return jdbc.query(FIND_CUSTOM_QUESTIONS_SQL, params, this::mapCustomQuestion);
    }

    public Optional<CustomQuestionRow> findCustomQuestion(UUID userId, UUID customQuestionId) {
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("customQuestionId", customQuestionId);
        return jdbc.query(FIND_CUSTOM_QUESTION_BY_ID_SQL, params, this::mapCustomQuestion)
                .stream().findFirst();
    }

    public int setCustomQuestionActive(UUID customQuestionId, boolean active) {
        var params = new MapSqlParameterSource()
                .addValue("customQuestionId", customQuestionId)
                .addValue("active", active);
        return jdbc.update(SET_CUSTOM_QUESTION_ACTIVE_SQL, params);
    }

    // ── Mappers ────────────────────────────────────────────────────────────

    private SetQuestionRow mapSetQuestion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SetQuestionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("question_id", UUID.class),
                rs.getString("question_text"),
                rs.getString("answer"),
                rs.getInt("sort_order"),
                rs.getBoolean("active"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_code"));
    }

    private CustomQuestionRow mapCustomQuestion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CustomQuestionRow(
                rs.getObject("id", UUID.class),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getInt("sort_order"),
                rs.getBoolean("active"));
    }
}
