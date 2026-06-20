# JDBC-Only Tables

The following tables have **no JPA entity or Spring Data repository**. They are accessed exclusively via `NamedParameterJdbcTemplate`.

| Table | Reason |
|---|---|
| `user_discovery_actions` | High-write, `ON CONFLICT DO NOTHING`, complex EXISTS subqueries |
| `user_daily_limits` | Composite PK `(user_id, limit_date)`, requires `SELECT FOR UPDATE` row locking |
| `user_blocks` | Composite PK `(blocker_user_id, blocked_user_id)`, insert + select only |
| `payment_events` | Insert-only with `ON CONFLICT (provider_event_id) DO NOTHING` |
| `audit_log` | Insert-only, nullable `actor_user_id`, never queried by client |

Do not add JPA entities or repositories for these tables.
