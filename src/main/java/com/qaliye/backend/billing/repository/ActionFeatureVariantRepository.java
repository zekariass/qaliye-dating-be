package com.qaliye.backend.billing.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages action_feature_variants — configurable variants of a fundamental
 * feature action (e.g. LIKE -> HEART, ROSE, BUNA, CHOCOLATE, FLOWERS, RING).
 *
 * A variant's code is unique per feature action, not globally.
 */
@Repository
public class ActionFeatureVariantRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ActionFeatureVariantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record VariantRow(
            UUID id,
            UUID featureActionId,
            String featureActionCode,
            String code,
            String name,
            String description,
            String icon,
            boolean active,
            int sortOrder
    ) {}

    private static final String FIND_BY_ACTION_CODE_AND_VARIANT_CODE_SQL = """
            SELECT afv.id, afv.feature_action_id, fa.code AS feature_action_code,
                   afv.code, afv.name, afv.description, afv.icon, afv.active, afv.sort_order
            FROM action_feature_variants afv
            JOIN feature_actions fa ON fa.id = afv.feature_action_id
            WHERE fa.code = :actionCode
              AND afv.code = :variantCode
            """;

    private static final String FIND_ACTIVE_BY_ACTION_CODE_SQL = """
            SELECT afv.id, afv.feature_action_id, fa.code AS feature_action_code,
                   afv.code, afv.name, afv.description, afv.icon, afv.active, afv.sort_order
            FROM action_feature_variants afv
            JOIN feature_actions fa ON fa.id = afv.feature_action_id
            WHERE fa.code = :actionCode
              AND afv.active = TRUE
              AND fa.active = TRUE
              AND fa.has_variants = TRUE
            ORDER BY afv.sort_order ASC, afv.name ASC
            """;

    private static final String FIND_ALL_BY_ACTION_CODE_SQL = """
            SELECT afv.id, afv.feature_action_id, fa.code AS feature_action_code,
                   afv.code, afv.name, afv.description, afv.icon, afv.active, afv.sort_order
            FROM action_feature_variants afv
            JOIN feature_actions fa ON fa.id = afv.feature_action_id
            WHERE fa.code = :actionCode
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT afv.id, afv.feature_action_id, fa.code AS feature_action_code,
                   afv.code, afv.name, afv.description, afv.icon, afv.active, afv.sort_order
            FROM action_feature_variants afv
            JOIN feature_actions fa ON fa.id = afv.feature_action_id
            WHERE afv.id = :id
            """;

    /**
     * Finds a variant by its owning feature action code and variant code, regardless
     * of active/disabled state. Used to resolve historical variant metadata for
     * previously created discovery actions even after the variant is disabled.
     */
    public Optional<VariantRow> findByActionCodeAndVariantCode(String actionCode, String variantCode) {
        if (variantCode == null) return Optional.empty();
        var params = new MapSqlParameterSource()
                .addValue("actionCode", actionCode)
                .addValue("variantCode", variantCode);
        return jdbc.query(FIND_BY_ACTION_CODE_AND_VARIANT_CODE_SQL, params, this::mapRow)
                .stream().findFirst();
    }

    public Optional<VariantRow> findById(UUID id) {
        return jdbc.query(FIND_BY_ID_SQL, new MapSqlParameterSource("id", id), this::mapRow)
                .stream().findFirst();
    }

    /**
     * Returns the currently active, selectable variants for a feature action
     * (e.g. LIKE), ordered by sort_order. Only returned when the owning feature
     * action itself is active and has_variants = TRUE.
     */
    public List<VariantRow> findActiveByActionCode(String actionCode) {
        return jdbc.query(FIND_ACTIVE_BY_ACTION_CODE_SQL,
                new MapSqlParameterSource("actionCode", actionCode), this::mapRow);
    }

    /**
     * Returns every variant (active or disabled) for a feature action, regardless of
     * the owning feature action's active/has_variants state. Used to resolve display
     * metadata for historical records (e.g. a LIKE sent with a since-disabled variant).
     */
    public List<VariantRow> findAllByActionCode(String actionCode) {
        return jdbc.query(FIND_ALL_BY_ACTION_CODE_SQL,
                new MapSqlParameterSource("actionCode", actionCode), this::mapRow);
    }

    private VariantRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new VariantRow(
                rs.getObject("id", UUID.class),
                rs.getObject("feature_action_id", UUID.class),
                rs.getString("feature_action_code"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("icon"),
                rs.getBoolean("active"),
                rs.getInt("sort_order")
        );
    }
}
