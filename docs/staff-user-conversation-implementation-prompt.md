# Backend AI Agent Implementation Brief: Add Isolated Staff–User Support Chat

## Objective

Extend the existing backend with a separate support-chat feature for communication between one application user and authorized support staff.

This feature is not user-to-user messaging and must remain completely isolated from the existing real-time user chat.

Use the provided database migration as the source of truth:

- `qaliye_support_chat_role_based.sql`

The support feature must be implemented additively. Do not refactor, rename, reuse, or alter existing user-to-user chat tables, entities, repositories, services, controllers, WebSocket/STOMP handlers, Supabase Realtime subscriptions, message events, DTOs, routes, or behavior.

## Non-negotiable constraints

1. Do not modify or break the existing real-time user-to-user chat.
2. Do not use the existing chat tables or message model for support messages.
3. Do not add support tables to Supabase Realtime publications.
4. Do not create WebSocket, STOMP, SSE, socket, or realtime-event behavior for support chat.
5. Implement support chat using normal authenticated HTTP APIs and database queries/RPC calls.
6. Every application user has exactly one support conversation, created automatically when the user is created.
7. A new conversation starts as `IDLE` and must not appear in the staff work queue until the user sends a support message.
8. Only the conversation owner may send a user support message.
9. Only application users whose existing `app_users.role` is `ADMIN` or `MODERATOR` may access staff support APIs, send staff replies, add internal notes, assign conversations, change priority, close conversations, or reopen them.
10. Never trust `userId`, `staffUserId`, `senderType`, ownership, or staff status supplied by a client. Derive the authenticated application user from the existing security context.
11. Do not expose internal notes, backend metadata, request hashes, storage paths, staff-only operational data, or per-staff read records to ordinary users.
12. Keep changes focused on this feature. Avoid unrelated cleanup, formatting, dependency upgrades, package moves, or architecture changes.

## First inspect the existing project

Before writing code, inspect and follow the project's existing conventions for:

- Spring Boot version and Java version
- authentication principal and application-user lookup
- authorization annotations and how `ADMIN` and `MODERATOR` are represented as Spring Security authorities
- controllers and API response envelopes
- exception handling and validation
- database access: JPA, JDBC, jOOQ, Supabase client, or another method
- migration tool and migration naming: Flyway, Liquibase, or existing SQL scripts
- Supabase/PostgreSQL configuration
- file-storage integration
- tests and test fixtures

Reuse those conventions. Do not introduce a second persistence framework or a new authentication mechanism.

## Database migration

Add the supplied support-chat SQL as a new forward-only migration. Do not edit an already-applied migration.

The migration contains dedicated support objects such as:

- `support_conversations`
- `support_messages`
- `support_attachments`
- `support_internal_notes`
- `support_conversation_staff_reads`
- support-only PostgreSQL functions/RPCs
- RLS and restricted grants
- automatic default-conversation provisioning and existing-user backfill

The migration creates five new support tables. It does not create a separate support-staff membership table; staff authorization is derived directly from `public.app_users.role`.

Confirm the migration does not alter any existing user-chat table, trigger, function, publication, or policy.

If the actual application-user table or ID type differs from `public.app_users(id UUID)`, adapt only the support migration references required to match the existing schema. Do not alter the application-user model unless strictly necessary.

If `app_users` includes non-customer system accounts that must not receive support conversations, use the existing account-type field to restrict the provisioning trigger. Do not invent a new account taxonomy.

## Package/module isolation

Place all new backend code in a dedicated support package or module consistent with the repository structure, for example:

```text
.../support/
    api/
    dto/
    service/
    repository/
    storage/
    security/
```

Use names beginning with `Support...` where practical. Do not place support logic in existing user-chat classes.

## Identity and authorization

### User operations

Obtain the current authenticated application user through the existing security/principal service. Never accept the acting user ID from the request body or query string.

For every user operation, enforce that the requested support conversation belongs to the authenticated user. Since each user has one conversation, user-facing APIs should generally resolve it by authenticated user ID rather than accepting an arbitrary conversation ID.

### Staff operations

Use the existing `public.app_users.role` column as the single source of truth for support-staff authorization.

A staff operation is permitted only when the authenticated application user has one of these exact roles:

```text
ADMIN
MODERATOR
```

At the Spring controller or service boundary, use the project's existing role mapping. For example, use `hasAnyRole('ADMIN', 'MODERATOR')` when the application exposes `ROLE_ADMIN` and `ROLE_MODERATOR`, or use the equivalent `hasAnyAuthority(...)` form when authorities are stored without the `ROLE_` prefix. Follow the existing security convention rather than introducing a second role system.

The supplied SQL independently validates staff operations with the equivalent database rule:

```sql
u.role::TEXT IN ('ADMIN', 'MODERATOR')
```

This database check is required for defense in depth. Do not remove it merely because the HTTP route is protected.

For every staff mutation RPC, pass the authenticated staff application-user ID resolved by the server. Never accept the acting staff ID or role from the request.

Do not create a separate support-staff membership table, seed process, or support-specific staff-membership endpoint. Do not copy role values into another table. If an administrator or moderator loses that role in `app_users`, subsequent support-staff operations must be rejected automatically.

## Required user APIs

Follow the project's route naming and response conventions. The following behavior must be available; exact paths may be adapted to existing conventions.

### Get the user's support conversation

`GET /api/support/conversation`

Return only user-safe fields, including:

- conversation ID
- status
- public read cursors or computed unread count
- last public message timestamp and sender type
- closed timestamp
- created timestamp

Do not return priority, assignment, internal activity timestamps, closing staff identity, or backend-only fields unless already explicitly required by the product.

### List public support messages

`GET /api/support/conversation/messages?beforeSequence={value}&limit={value}`

Requirements:

- resolve the conversation from the authenticated user
- use sequence-based cursor pagination
- sort results consistently
- cap page size to a safe maximum
- include attachment display metadata
- do not expose sender UUID, request hash, client idempotency key, raw metadata, storage bucket, or storage path
- return no internal notes

### Send a support message

`POST /api/support/conversation/messages`

Support text messages, attachment-only messages, and messages with both text and attachments.

Require a client-generated UUID idempotency key such as `clientMessageId`.

The backend must:

1. derive the authenticated user ID
2. resolve the user's default support conversation
3. validate body and files
4. upload files to the private support bucket using backend credentials
5. call `append_support_user_message`
6. delete newly uploaded objects if the RPC fails
7. return the persisted public message DTO

Do not insert directly into support tables.

A user message may reopen a closed support conversation as defined by the supplied database function.

### Mark the conversation read

`POST /api/support/conversation/read`

Accept only the last public sequence the client has displayed. Derive the user ID on the server and call `mark_support_conversation_read_by_user`.

### Close the conversation

`POST /api/support/conversation/close`

Resolve the authenticated user's support conversation and call `close_support_conversation_by_user`.

### Get an attachment download URL

`GET /api/support/attachments/{attachmentId}/download-url`

The backend must verify through the support tables that the attachment belongs to the authenticated user's support conversation. Return a short-lived signed URL from the private bucket. Never return the raw service-role key or expose unrestricted bucket access.

## Required staff APIs

Protect all staff routes with the existing authorization mechanism and require the authenticated user to have role `ADMIN` or `MODERATOR`. Apply the same rule consistently to reads and writes; do not protect only mutation endpoints.

### Staff queue/list

`GET /api/staff/support/conversations`

Support filters needed by the application, at minimum:

- status
- assigned to current staff
- unassigned
- priority
- cursor/page size

The default active queue should show `WAITING_STAFF` conversations ordered by:

1. priority ascending, where 1 is highest
2. `waiting_since` ascending
3. stable ID tie-breaker

Never include `IDLE` conversations in the active queue.

Return staff-safe summary fields and basic user-identification fields only by joining through the project's existing user representation. Do not expose secrets or authentication data.

### Staff conversation detail

`GET /api/staff/support/conversations/{conversationId}`

Return the support conversation, public messages, public attachments, assignment, priority, read state, and product-approved user profile fields.

Keep internal notes in a clearly separate response field or endpoint so they can never be serialized into a user response accidentally.

### Staff public messages

`GET /api/staff/support/conversations/{conversationId}/messages`

Use sequence cursor pagination.

`POST /api/staff/support/conversations/{conversationId}/messages`

Derive the staff user ID and call `append_support_staff_message`. Require `clientMessageId`. Support attachments with the same private-storage and compensating-cleanup flow used for user messages.

Staff may not send the first public message while a conversation is `IDLE`. Staff must explicitly reopen a closed conversation before replying.

### Internal notes

`GET /api/staff/support/conversations/{conversationId}/notes`

`POST /api/staff/support/conversations/{conversationId}/notes`

Derive the acting staff ID and use `append_support_internal_note`. Require a client note UUID for idempotency. Internal notes must never be returned by any user endpoint.

### Read cursor

`POST /api/staff/support/conversations/{conversationId}/read`

Call `mark_support_conversation_read_by_staff` with the authenticated staff ID.

### Assignment

`PATCH /api/staff/support/conversations/{conversationId}/assignment`

Call `assign_support_conversation`. Validate that the target application user exists and has `app_users.role` equal to `ADMIN` or `MODERATOR`. Support unassignment if allowed by the SQL function.

### Priority

`PATCH /api/staff/support/conversations/{conversationId}/priority`

Accept values 1 through 5 and call `set_support_conversation_priority`.

### Close and reopen

`POST /api/staff/support/conversations/{conversationId}/close`

Call `close_support_conversation_by_staff`.

`POST /api/staff/support/conversations/{conversationId}/reopen`

Call `reopen_support_conversation_by_staff`.

## Database access rules

Use the support mutation functions/RPCs for writes. Do not perform direct inserts or state-changing updates against support tables from application code.

Read queries may use the project's existing repository/data-access method. Keep all SQL scoped to support tables.

Map expected PostgreSQL exceptions into appropriate API errors, including:

- unauthorized staff whose `app_users.role` is not `ADMIN` or `MODERATOR`
- support conversation not found
- invalid state transition
- conversation closed
- invalid attachment metadata
- body/attachment validation failure
- idempotency conflict

Do not leak raw SQL, function definitions, credentials, stack traces, or database connection details in API responses.

## Non-realtime behavior

This support feature is intentionally not realtime.

Do not:

- create WebSocket endpoints
- reuse existing chat socket destinations
- publish support events to the existing message broker
- add support tables to Supabase Realtime
- emit existing user-chat domain events
- change existing unread-chat counters
- change existing chat notification behavior

The support clients will refresh or poll the REST APIs as needed. The backend only needs durable HTTP operations and paginated reads.

## Attachment handling

Use a private bucket. Prefer the bucket name defined by the migration: `support-attachments`.

Recommended object path:

```text
support/{conversationId}/{clientMessageId}/{attachmentIndex}-{randomUuid}-{sanitizedFileName}
```

Requirements:

- generate paths on the backend
- normalize/sanitize display filenames
- prevent traversal and unsafe paths
- enforce at most 10 files per message
- enforce the database maximum of 25 MiB per file before upload
- use an allowlist of content types suitable for the product
- never trust browser-provided MIME type alone when stronger detection exists in the project
- retain the original safe display filename separately from the storage object path
- remove uploaded objects when message creation fails
- use short-lived signed download URLs
- log cleanup failures without exposing storage details to clients

For idempotent request retries, avoid creating duplicate objects. Prefer deterministic paths based on conversation ID, client message ID, and attachment index, or detect and reuse already-uploaded objects when the same idempotency request is retried.

## DTO and serialization requirements

Create support-specific DTOs. Do not reuse existing realtime-chat DTOs.

User message responses may include:

- message ID
- sequence number
- sender type (`USER` or `STAFF`)
- body
- created timestamp
- attachment display metadata

They must not include:

- sender user ID
- client message ID
- request hash
- database metadata JSON
- storage bucket/path
- internal notes

Staff DTOs may include additional operational fields, but still must not expose credentials, request hashes, or other secrets.

Use the project's normal timestamp format and API envelope.

## Configuration and environment variables

Reuse existing database, Supabase, and service-role configuration. Do not add duplicate credentials under new names.

Add environment variables only where configuration is genuinely deployment-specific. Suggested names, only if equivalent configuration does not already exist:

```text
SUPPORT_ATTACHMENTS_BUCKET=support-attachments
SUPPORT_ATTACHMENT_SIGNED_URL_TTL_SECONDS=300
SUPPORT_ALLOWED_CONTENT_TYPES=image/jpeg,image/png,image/webp,application/pdf,text/plain
```

Do not place secrets in source code. Use the existing secret-management approach.

Do not add environment variables for the authorized role names. `ADMIN` and `MODERATOR` are existing application authorization values and must remain aligned with `app_users.role` and the supplied SQL migration.

Keep hard security limits synchronized with the database. Do not make an environment variable capable of raising limits above the database constraints.

## Transactions and consistency

- Database message creation and attachment metadata insertion are atomic inside the supplied RPC.
- Storage upload and database commit cannot be one PostgreSQL transaction, so implement compensating deletion for uploaded objects when the RPC fails.
- Never update conversation status separately from inserting a public message.
- Do not manually allocate message sequence numbers.
- Let the database functions serialize sequence allocation.
- Preserve the supplied idempotency behavior and return the existing message for an exact retry.
- Return a conflict response when an idempotency key is reused with different content.

## Logging and observability

Add focused structured logs for support operations using the project's logging conventions:

- actor application-user ID
- conversation ID
- operation type
- message or note ID after persistence
- attachment count
- success/failure category

Do not log message bodies, internal-note bodies, signed URLs, access tokens, service keys, or sensitive attachment contents.

Do not mix support metrics into existing realtime-chat metrics unless they are clearly namespaced as support metrics.

## Tests

Add tests using the repository's existing test framework.

### Migration/database integration tests

Verify:

- a new application user receives exactly one `IDLE` support conversation
- backfill does not create duplicates
- an empty `IDLE` conversation is absent from the waiting queue
- the first user message changes status to `WAITING_STAFF`
- a staff reply changes status to `WAITING_USER`
- a user follow-up retains the original `waiting_since` while already waiting for staff
- only the owner can create a user message
- users whose `app_users.role` is not `ADMIN` or `MODERATOR` cannot perform staff actions
- both `ADMIN` and `MODERATOR` can perform authorized support-staff operations
- changing a staff user's role to a non-staff role immediately prevents subsequent staff operations
- assignment accepts only users whose role is `ADMIN` or `MODERATOR`
- public message sequences are monotonic under concurrent inserts
- internal notes do not consume public message sequences
- exact idempotent retries return the same row
- mismatched retries fail with an idempotency conflict
- message plus attachment metadata rolls back together on failure
- users cannot read another user's support data
- users cannot read internal notes or hidden attachment paths
- closing and reopening follow the allowed state transitions

### API/security tests

Verify:

- unauthenticated support APIs are rejected
- user APIs ignore/reject forged user or sender identities
- staff APIs reject users whose role is neither `ADMIN` nor `MODERATOR`
- staff APIs accept both authorized roles using the project's actual `ROLE_` prefix or authority mapping
- forged role or staff-user fields in request bodies are ignored or rejected
- user message responses do not serialize internal/backend-only fields
- user endpoints never return internal notes
- attachment URL endpoint checks ownership
- file limits and content-type rules are enforced
- staff cannot reply to an `IDLE` conversation
- staff cannot reply to a closed conversation until it is reopened

### Regression tests for existing chat

Run the complete existing user-to-user chat test suite.

Add a focused regression assertion that:

- existing chat REST routes are unchanged
- existing WebSocket/STOMP destinations are unchanged
- existing chat database tables are unchanged
- existing realtime events and listeners are unchanged
- existing chat unread counts and notifications are unchanged
- support messages never appear in existing user-chat queries
- existing user messages never appear in support queries

## Deliverables

1. A new versioned database migration containing the supplied support schema, adapted only where necessary for the existing project.
2. Dedicated support repositories/data-access components.
3. Support services implementing user and staff workflows.
4. Separate user and staff REST controllers.
5. Support-specific request/response DTOs and validation.
6. Private attachment upload and signed-download integration.
7. Authorization and exception mapping.
8. Database, API, security, and existing-chat regression tests.
9. Minimal configuration documentation for any newly required environment variables.
10. A concise implementation summary listing every changed and added file.

## Completion criteria

The implementation is complete only when:

- every application user has exactly one default support conversation
- support communication is only between that user and staff whose `app_users.role` is `ADMIN` or `MODERATOR`
- support messaging works through REST without realtime infrastructure
- internal notes are staff-only
- attachments are private and authorized
- idempotency and pagination work
- the staff queue excludes untouched `IDLE` conversations
- no existing user-to-user chat code, schema, API, realtime behavior, or tests are adversely affected
- all new tests and the existing full test suite pass

When uncertain, choose the smallest additive change that preserves existing behavior. Do not perform unrelated refactors.