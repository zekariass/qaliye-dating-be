--To reset the `user_daily_limits` table for the test user. Here are the options:

### Option 1: Reset today's counters for a specific user

```sql
UPDATE user_daily_limits
SET likes_used = 0, super_likes_used = 0, rewinds_used = 0
WHERE user_id = '<USER_UUID>' AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE;
```

### Option 2: Delete today's row (it will be recreated on next action)

```sql
DELETE FROM user_daily_limits
WHERE user_id = '<USER_UUID>' AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE;
```

### Option 3: Reset all users (useful for dev/staging)

```sql
DELETE FROM user_daily_limits
WHERE limit_date = (NOW() AT TIME ZONE 'UTC')::DATE;
```

### Option 4: Reset a specific action only

```sql
-- Reset only likes
UPDATE user_daily_limits SET likes_used = 0
WHERE user_id = '<USER_UUID>' AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE;

-- Reset only superlikes
UPDATE user_daily_limits SET super_likes_used = 0
WHERE user_id = '<USER_UUID>' AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE;

-- Reset only rewinds
UPDATE user_daily_limits SET rewinds_used = 0
WHERE user_id = '<USER_UUID>' AND limit_date = (NOW() AT TIME ZONE 'UTC')::DATE;
```

### To find your test user's UUID

```sql
SELECT id, display_name FROM app_users WHERE display_name ILIKE '%test%';
```

### Note: Credits are separate

The above resets **daily quota counters** only. If the user has exhausted **purchased credits** (superlike/rewind/boost credits), those are stored in `user_entitlement_credit_lots` and consumed via FIFO. To reset those:

```sql
-- Give the user 10 fresh superlike credits
INSERT INTO user_entitlement_credit_lots (user_id, entitlement_type, quantity_remaining, expires_at)
VALUES ('<USER_UUID>', 'SUPERLIKE_CREDIT', 10, NOW() + INTERVAL '30 days');

-- Give 10 rewind credits
INSERT INTO user_entitlement_credit_lots (user_id, entitlement_type, quantity_remaining, expires_at)
VALUES ('<USER_UUID>', 'REWIND_CREDIT', 10, NOW() + INTERVAL '30 days');

-- Give 5 boost credits
INSERT INTO user_entitlement_credit_lots (user_id, entitlement_type, quantity_remaining, expires_at)
VALUES ('<USER_UUID>', 'BOOST_CREDIT', 5, NOW() + INTERVAL '30 days');
```

After resetting, call `GET /api/v1/billing/entitlements` to verify the refreshed state.