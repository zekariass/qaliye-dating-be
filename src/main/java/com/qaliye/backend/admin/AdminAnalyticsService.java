package com.qaliye.backend.admin;

import com.qaliye.backend.user.UserStatusService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminAnalyticsService {

    private static final String USER_STATS_SQL = """
            SELECT
                COUNT(*) AS total_users,
                COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active_users,
                COUNT(*) FILTER (WHERE status = 'SUSPENDED') AS suspended_users,
                COUNT(*) FILTER (WHERE status = 'DEACTIVATED') AS deactivated_users,
                COUNT(*) FILTER (WHERE status = 'BANNED') AS banned_users,
                COUNT(*) FILTER (WHERE status = 'DELETED') AS deleted_users,
                COUNT(*) FILTER (WHERE role = 'ADMIN') AS admins,
                COUNT(*) FILTER (WHERE role = 'MODERATOR') AS moderators,
                COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '24 hours') AS new_users_24h,
                COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '7 days') AS new_users_7d,
                COUNT(*) FILTER (WHERE last_active_at >= NOW() - INTERVAL '24 hours') AS dau,
                COUNT(*) FILTER (WHERE last_active_at >= NOW() - INTERVAL '7 days') AS wau,
                COUNT(*) FILTER (WHERE last_active_at >= NOW() - INTERVAL '30 days') AS mau
            FROM app_users
            """;

    private static final String PROFILE_STATS_SQL = """
            SELECT
                COUNT(*) AS total_profiles,
                COUNT(*) FILTER (WHERE is_onboarded) AS onboarded_profiles,
                COUNT(*) FILTER (WHERE is_verified) AS verified_profiles,
                COUNT(*) FILTER (WHERE is_visible) AS visible_profiles,
                AVG(profile_completion_score)::int AS avg_completion_score
            FROM profiles
            """;

    private static final String MATCH_STATS_SQL = """
            SELECT
                COUNT(*) AS total_matches,
                COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active_matches,
                COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '24 hours') AS new_matches_24h,
                COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '7 days') AS new_matches_7d
            FROM matches
            """;

    private static final String MODERATION_STATS_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE moderation_status = 'PENDING') AS photos_pending,
                COUNT(*) FILTER (WHERE moderation_status = 'MANUAL_REVIEW') AS photos_manual_review,
                COUNT(*) FILTER (WHERE moderation_status = 'APPROVED') AS photos_approved,
                COUNT(*) FILTER (WHERE moderation_status = 'REJECTED') AS photos_rejected
            FROM profile_photos
            WHERE deleted_at IS NULL
            """;

    private static final String REPORT_STATS_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'PENDING') AS reports_pending,
                COUNT(*) FILTER (WHERE status = 'UNDER_REVIEW') AS reports_under_review,
                COUNT(*) FILTER (WHERE status = 'RESOLVED_NO_ACTION') AS reports_resolved_no_action,
                COUNT(*) FILTER (WHERE status = 'RESOLVED_BANNED') AS reports_resolved_banned,
                COUNT(*) FILTER (WHERE created_at >= NOW() - INTERVAL '24 hours') AS new_reports_24h
            FROM user_reports
            """;

    private static final String ORDER_STATS_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'VERIFIED') AS verified_orders,
                COUNT(*) FILTER (WHERE status IN ('CREATED', 'AWAITING_PAYMENT', 'RECEIPT_SUBMITTED', 'VERIFICATION_PENDING')) AS pending_orders,
                COUNT(*) FILTER (WHERE status = 'MANUAL_REVIEW') AS review_orders,
                COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_orders,
                COUNT(*) FILTER (WHERE status = 'REJECTED') AS rejected_orders
            FROM payment_orders
            """;

    private static final String REVENUE_STATS_SQL = """
            SELECT
                COALESCE(SUM(amount_minor_units) FILTER (
                    WHERE status = 'COMPLETED' AND created_at >= NOW() - INTERVAL '24 hours'
                ), 0) AS revenue_24h,
                COALESCE(SUM(amount_minor_units) FILTER (
                    WHERE status = 'COMPLETED' AND created_at >= NOW() - INTERVAL '7 days'
                ), 0) AS revenue_7d,
                COALESCE(SUM(amount_minor_units) FILTER (
                    WHERE status = 'COMPLETED' AND created_at >= NOW() - INTERVAL '30 days'
                ), 0) AS revenue_30d
            FROM transactions
            """;

    private static final String SUBSCRIPTION_STATS_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active_subscriptions,
                COUNT(*) FILTER (WHERE status = 'CANCELED') AS cancelled_subscriptions,
                COUNT(*) FILTER (WHERE status = 'PENDING_VERIFICATION') AS pending_subscriptions
            FROM user_subscriptions
            """;

    private static final String NOTIFICATION_STATS_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'PENDING') AS notifications_pending,
                COUNT(*) FILTER (WHERE status = 'FANOUT_COMPLETE') AS notifications_fanout_complete,
                COUNT(*) FILTER (WHERE status = 'PROCESSING') AS notifications_processing,
                COUNT(*) FILTER (WHERE status = 'FAILED') AS notifications_failed,
                COUNT(*) FILTER (WHERE status = 'SKIPPED') AS notifications_skipped
            FROM notification_outbox_events
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final UserStatusService userStatusService;

    public AdminAnalyticsService(NamedParameterJdbcTemplate jdbc,
                                 UserStatusService userStatusService) {
        this.jdbc = jdbc;
        this.userStatusService = userStatusService;
    }

    public Map<String, Object> getDashboard(UUID callerId) {
        requireModeratorOrAdmin(callerId);

        Map<String, Object> dashboard = new LinkedHashMap<>();

        dashboard.put("users", querySingleRow(USER_STATS_SQL));
        dashboard.put("profiles", querySingleRow(PROFILE_STATS_SQL));
        dashboard.put("matches", querySingleRow(MATCH_STATS_SQL));
        dashboard.put("moderation", querySingleRow(MODERATION_STATS_SQL));
        dashboard.put("reports", querySingleRow(REPORT_STATS_SQL));
        Map<String, Object> revenue = new LinkedHashMap<>();
        revenue.putAll(querySingleRow(ORDER_STATS_SQL));
        revenue.putAll(querySingleRow(REVENUE_STATS_SQL));
        dashboard.put("revenue", revenue);
        dashboard.put("subscriptions", querySingleRow(SUBSCRIPTION_STATS_SQL));
        dashboard.put("notifications", querySingleRow(NOTIFICATION_STATS_SQL));

        return dashboard;
    }

    private Map<String, Object> querySingleRow(String sql) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, Map.of());
        if (rows.isEmpty()) {
            return Map.of();
        }
        return rows.get(0);
    }

    private void requireModeratorOrAdmin(UUID callerId) {
        UserStatusService.UserStatus status = userStatusService.getStatus(callerId);
        if (status == null
                || (!"MODERATOR".equals(status.role()) && !"ADMIN".equals(status.role()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "access_denied");
        }
    }
}
