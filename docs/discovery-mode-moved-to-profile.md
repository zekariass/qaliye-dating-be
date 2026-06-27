# Frontend Update Prompt: Discovery Mode Moved to Profile

## Task
Update the frontend application to reflect the backend change that moved `discovery_mode` from the `DiscoveryPreferences` entity into the `Profile` entity.

## Backend Changes Summary

1. **Database schema migration `V6__move_discovery_mode_to_profile.sql`:**
   - Added `discovery_mode VARCHAR(20) NOT NULL DEFAULT 'PUBLIC'` to `profiles` with a CHECK constraint on `('PUBLIC', 'INCOGNITO')`.
   - Backfilled data from `discovery_preferences` to `profiles`.
   - Dropped `discovery_mode` from `discovery_preferences`.

2. **API contract changes:**
   - `GET /api/v1/profile/me` now returns `discovery_mode` as a top-level field on the profile object, **not** inside `discovery_preferences`.
   - `PUT /api/v1/profile/me` now accepts `discovery_mode` as an optional top-level field (allowed values: `PUBLIC` or `INCOGNITO`).
   - `GET /api/v1/profile/me/preferences` and `PUT /api/v1/profile/me/preferences` no longer contain `discovery_mode` at all.
   - The old `GET /api/v1/discovery/preferences` and `PUT /api/v1/discovery/preferences` endpoints no longer return or accept `discovery_mode`.

3. **DTO/entity changes:**
   - `ProfileMeDto` now has a `discoveryMode: string` field alongside `isVisible`.
   - `DiscoveryPreferencesDto` no longer has a `discoveryMode` field.
   - `ProfileUpdateRequest` now accepts an optional `discoveryMode` field.
   - `UpdatePreferencesRequest` (old endpoint) no longer accepts `discoveryMode`.

## Frontend Work Required

1. **Update TypeScript interfaces / types:**
   - Add `discoveryMode?: 'PUBLIC' | 'INCOGNITO'` to the `Profile` type.
   - Remove `discoveryMode` from the `DiscoveryPreferences` type.
   - Add `discoveryMode?: 'PUBLIC' | 'INCOGNITO'` to the `ProfileUpdateRequest` type.
   - Remove `discoveryMode` from any preference-update request type.

2. **Update forms and screens:**
   - Move the discovery mode selector from the **Discovery Preferences** form/screen to the **Profile Edit** screen.
   - If there is a dedicated "visibility / discovery settings" section, place `discovery_mode` there next to `is_visible`.
   - Ensure the selector only sends `PUBLIC` or `INCOGNITO`.
   - When editing discovery preferences, remove any discovery-mode toggle/selector so the request no longer includes it.

3. **Update API calls:**
   - `GET /api/v1/profile/me` — read `discoveryMode` from the profile root (not `discoveryPreferences.discoveryMode`).
   - `PUT /api/v1/profile/me` — include `discoveryMode` when the user changes it.
   - `PUT /api/v1/profile/me/preferences` and `PUT /api/v1/discovery/preferences` — remove `discoveryMode` from the payload.

4. **Update Redux / context / stores (if applicable):**
   - Move `discoveryMode` from the discovery-preferences slice to the profile slice.
   - Update selectors, reducers, and initial state accordingly.

5. **Validation and UX:**
   - Default to `PUBLIC` if the field is missing or null.
   - Show validation errors if the value is not `PUBLIC` or `INCOGNITO` before submitting.

## Output
Make the minimal, precise changes needed so the frontend stays consistent with the new backend contract. Do not add new backend logic or change backend files.
