# New Production Database Migration Plan

Follow these steps **in order** every time a new Supabase project is created for production.

---

## Step 1 — Create Supabase Project

1. Go to [supabase.com](https://supabase.com) → New Project.
2. Choose the correct **region** (closest to your users).
3. Set a strong **database password** — save it securely.
4. Wait for the project to finish provisioning.

---

## Step 2 — Collect Credentials

From **Project Settings → API**, note:

| Variable | Where to find |
|---|---|
| `SUPABASE_URL` | Project URL (e.g. `https://xxxx.supabase.co`) |
| `SUPABASE_ANON_KEY` | `anon` / `public` key |
| `SUPABASE_SERVICE_ROLE_KEY` | `service_role` key (keep secret) |
| `DB_URL` | Settings → Database → Connection string (JDBC) |

---

## Step 3 — Configure Backend

Update your environment variables (`.env`, server config, or CI secrets):

```
SUPABASE_URL=https://<new-project-ref>.supabase.co
SUPABASE_ANON_KEY=<new-anon-key>
SUPABASE_SERVICE_ROLE_KEY=<new-service-role-key>
SPRING_DATASOURCE_URL=jdbc:postgresql://db.<new-project-ref>.supabase.co:5432/postgres
SPRING_DATASOURCE_PASSWORD=<db-password>
```

---

## Step 4 — Apply Schema SQL Scripts

Flyway is **disabled** in production. All schema scripts must be run manually in the
**Supabase Dashboard → SQL Editor**.

### 4a — Run the full schema dump

```
docs/migration-plan/schemaFULL.sql
```

This is a `pg_dump --schema-only` snapshot of the production DB and contains
everything: all tables, indexes, FK constraints, triggers, functions, RLS enablement,
and all policies in the `public` schema.

> ⚠️ `schemaFULL.sql` must be regenerated whenever new migration files are added
> (see regeneration instructions at the bottom of this document).

### 4b — Run any new migration files added after the dump

If new `V*.sql` files have been added to `src/main/resources/db/migration/` since
`schemaFULL.sql` was last generated, run those files in version order on top:

```
-- example: if schemaFULL.sql was generated after V64, only run newer files
V65__some_new_change.sql
V66__another_change.sql
```

Skip this step if no new migration files exist since the last dump.

### 4c — Apply `realtime.messages` policies

`pg_dump --schema=public` excludes the `realtime` schema, so these policies are
**never captured in the dump** and must always be run manually.

In **Supabase Dashboard → SQL Editor**, run:

```
docs/migration-plan/realtime-policies.sql
```

This creates the `chat realtime receive` and `chat realtime publish ephemeral` policies
on `realtime.messages`, which authorize clients to subscribe to chat broadcast/presence
channels. Without them, real-time chat will not work for clients.

> ⚠️ Do **not** run V8/V9 directly for this step — they contain
> `ALTER TABLE realtime.messages ENABLE ROW LEVEL SECURITY` which fails in Supabase
> with "must be owner of table messages". `realtime-policies.sql` is the safe equivalent.

**Verify all policies applied:**
```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

---

## Step 5 — Create Storage Buckets

In **Supabase Dashboard → SQL Editor**, run the full script:

```
docs/migration-plan/create-storage-buckets.sql
```

This creates all 5 private buckets:
- `profile-photos` (10 MB, images)
- `verification-selfies` (10 MB, images)
- `chat-attachments` (25 MB, images + audio)
- `support-attachments` (50 MB, images + audio + PDF)
- `payment-receipts` (10 MB, images + PDF)

**Verify:** Dashboard → Storage → Buckets — all 5 should appear.

---

## Step 6 — Set Up Storage RLS Policies (Manual)

> ⚠️ Supabase restricts `CREATE POLICY` on `storage.objects` via SQL.
> These **must** be created via the Dashboard UI.

Go to **Dashboard → Storage → Policies** and create the following:

### `profile-photos` (4 policies)

| # | Name | Operation | Role | Expression |
|---|---|---|---|---|
| 1 | Users can upload their own profile photos | INSERT | authenticated | `bucket_id = 'profile-photos' AND (storage.foldername(name))[1] = (auth.uid())::text` |
| 2 | Authenticated users can read profile photos | SELECT | authenticated | `bucket_id = 'profile-photos'` |
| 3 | Users can update their own profile photos | UPDATE | authenticated | `bucket_id = 'profile-photos' AND (storage.foldername(name))[1] = (auth.uid())::text` |
| 4 | Users can delete their own profile photos | DELETE | authenticated | `bucket_id = 'profile-photos' AND (storage.foldername(name))[1] = (auth.uid())::text` |

### `verification-selfies` (2 policies)

| # | Name | Operation | Role | Expression |
|---|---|---|---|---|
| 5 | Users can upload their own verification selfies | INSERT | authenticated | `bucket_id = 'verification-selfies' AND (storage.foldername(name))[1] = (auth.uid())::text` |
| 6 | Users can read their own verification selfies | SELECT | authenticated | `bucket_id = 'verification-selfies' AND (storage.foldername(name))[1] = (auth.uid())::text` |

### `payment-receipts` (2 policies)

| # | Name | Operation | Role | Expression |
|---|---|---|---|---|
| 7 | Users can upload their own payment receipts | INSERT | authenticated | `bucket_id = 'payment-receipts' AND (storage.foldername(name))[1] = (auth.uid())::text` |
| 8 | Users can read their own payment receipts | SELECT | authenticated | `bucket_id = 'payment-receipts' AND (storage.foldername(name))[1] = (auth.uid())::text` |

> `chat-attachments` and `support-attachments` have **no client RLS policies** — all access goes through the backend service-role key.

---

## Step 7 — Seed Reference / Initial Data

The SQL migration scripts create tables but do **not** insert reference data.
See `docs/migration-plan/tables-need-initial-data.md` for the full list.

Insert seed data for:
- `subscription_plans` (FREE, PREMIUM)
- `subscription_products` (monthly, 3-month, 6-month)
- `consumable_products` (credit packages)
- `payment_offers` (pricing per country/platform)
- `payment_methods` (channels per market)
- `feature_actions` (LIKE, SUPER_LIKE, BOOST, etc.)
- `subscription_plan_limit_and_cost` (limits × actions)
- `country_settings` (per-country toggles)
- `languages` (catalog)
- `ethnicities` (catalog)
- `promotion_campaigns` (active trials / discounts)

> Source: use the seed INSERT scripts from your previous production DB dump,
> or the `docs/schemaOLD.sql` seed sections as a reference.
> Some data is also seeded in individual migration files (e.g. `V44__seed_promotion_campaigns.sql`,
> `V53__payment_system_redesign.sql`) — check those files for embedded INSERT statements.

**Verify:** Spot-check a few rows:
```sql
SELECT count(*) FROM subscription_plans;
SELECT count(*) FROM feature_actions;
SELECT count(*) FROM payment_methods;
SELECT count(*) FROM languages;
```

---

## Step 8 — Configure Supabase Auth

In **Dashboard → Authentication → Settings**:

- **JWT expiry** — set to your app's expected token lifetime
- **Email confirmations** — enable/disable per requirements
- **Redirect URLs** — add your app deep-link URLs (e.g. `qaliye://auth/callback`)
- **Email templates** — customise confirmation / password-reset emails if needed

---

## Step 9 — Migrate Storage Files (if migrating from old project)

> Skip this step if launching fresh with no existing users.

Storage files are **not included** in a Postgres dump. They must be copied manually.

### Option A — Supabase CLI (recommended)

```bash
# Download all objects from old project
supabase storage cp --recursive ss://profile-photos /tmp/profile-photos \
  --project-ref <OLD_PROJECT_REF> --token <OLD_SERVICE_ROLE_KEY>

# Upload to new project
supabase storage cp --recursive /tmp/profile-photos ss://profile-photos \
  --project-ref <NEW_PROJECT_REF> --token <NEW_SERVICE_ROLE_KEY>
```

Repeat for each bucket: `verification-selfies`, `chat-attachments`, `support-attachments`, `payment-receipts`.

### Option B — Script via Storage REST API

Use the Supabase Storage API (`/storage/v1/object/list` + `/storage/v1/object/{bucket}/{path}`)
with the service-role key to iterate and re-upload each object.

### Why this step is critical
Without it, `generateSignedUrl` returns `null` for every file reference in the DB,
causing 404 errors and potential NPEs in the backend.

---

## Step 10 — Migrate User Data (if migrating from old project)

> Skip if starting fresh (run `docs/migration-plan/clear-user-data.sql` instead to wipe test data).

If the old project's Postgres data needs to be copied:

1. **Dump** from old project (Supabase Dashboard → Database → Backups, or `pg_dump`).
2. **Restore** only user-data tables to the new project via `psql` or Supabase restore.
3. Do **not** restore schema DDL — schema is already applied by the SQL scripts in Step 4.

> ⚠️ Always migrate storage files (Step 9) alongside user data, otherwise
> DB rows will reference objects that don't exist in the new bucket.

---

## Step 11 — Smoke Test

Test these flows before switching live traffic:

- [ ] Register a new user → verify JWT issued
- [ ] Upload a profile photo → verify signed URL is returned
- [ ] Fetch another user's profile → verify photo URL loads
- [ ] Submit a payment receipt → verify upload succeeds
- [ ] Send a chat message → verify delivery
- [ ] Trigger a push notification → verify receipt
- [ ] Admin: create/view support conversation

---

## Step 12 — Switch Over

1. Update backend deployment environment variables to point to new Supabase project.
2. Redeploy the backend.
3. Monitor logs for 5–10 minutes for any auth, storage, or DB errors.
4. Keep the old project running (paused, not deleted) for at least 7 days as a rollback safety net.

---

## Common Errors & Fixes

| Error | Cause | Fix |
|---|---|---|
| `new row violates row level security policy` on photo upload | RLS policies not created on `storage.objects` | Complete Step 6 |
| `Object not found` / signed URL returns null | File missing in new storage bucket | Complete Step 9 |
| `NullPointerException` in `ProfileService` | Missing file causes `generateSignedUrl` to return null | Code fix already applied (`filter(Objects::nonNull)`) |
| `must be owner of table objects` (SQL error) | Cannot create storage RLS via SQL in Supabase | Use Dashboard UI (Step 6) |
| `cannot truncate a table referenced in a foreign key constraint` | Missing table in clear script | Add the table, or append `CASCADE` to the `TRUNCATE` |
| Real-time chat not working for clients | `realtime.messages` policies missing | Run V8 + V9 scripts (Step 4c) |

---

## Regenerating schemaFULL.sql

Run this after every new migration file is added and applied to production,
so `schemaFULL.sql` stays current for the next time a new DB is needed.

```bash
pg_dump \
  --schema-only \
  --no-owner \
  --schema=public \
  --format=plain \
  "postgresql://postgres:[PASSWORD]@db.[PROJECT-REF].supabase.co:5432/postgres" \
  > docs/migration-plan/schemaFULL.sql
```

> Remember: `realtime.messages` policies (Step 4c) are **never** captured in this dump
> and must always be run separately from V8 + V9.
