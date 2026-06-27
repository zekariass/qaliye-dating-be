# Qaliye — Profile API Frontend Integration Guide

**Base URL:** `https://<host>/api/v1`  
**Authentication:** Every request requires a Supabase JWT in the `Authorization` header.  
**Content-Type:** `application/json` for all request and response bodies.  
**Key naming:** All JSON keys are `snake_case`.

```
Authorization: Bearer <supabase_access_token>
```

---

## Shared Object Shapes

These objects are reused across multiple endpoints.

### `ProfileAddress`

```ts
interface ProfileAddress {
  id: string;            // UUID
  city: string;
  region: string | null;
  country_code: string;  // ISO-3166 alpha-2, e.g. "ET"
  country_name: string;
  formatted_address: string | null;
  location_source: "GPS" | "MANUAL";
}
```

### `ProfilePhoto`

```ts
interface ProfilePhoto {
  id: string;              // UUID
  photo_order: number;     // 0-based, contiguous
  is_primary: boolean;
  signed_url: string;      // Expires in 1 hour — refresh on next mount
  expires_at: string;      // ISO-8601 UTC instant, e.g. "2025-06-24T14:00:00Z"
  moderation_status: "PENDING" | "APPROVED" | "REJECTED";
}
```

### `DiscoveryPreferences`

```ts
interface DiscoveryPreferences {
  discovery_mode: "PUBLIC" | "INCOGNITO";
  interested_in_gender: "MALE" | "FEMALE";
  min_age: number;                          // 18–120
  max_age: number;                          // 18–120, >= min_age
  max_distance_km: number;                  // 1–500
  preferred_residency_types: Array<"ETHIOPIA" | "ERITREA" | "DIASPORA">;
  open_to_long_distance: boolean;
  open_to_relocation: boolean;
  show_verified_only: boolean;
}
```

### `ApiError` (all error responses)

```ts
interface ApiError {
  error: {
    code: string;
    message: string;
    details?: Record<string, string>; // field-level validation messages
  };
}
```

---

## Enum Reference

| Field | Accepted values |
|-------|----------------|
| `gender` | `MALE`, `FEMALE` |
| `residency_type` | `ETHIOPIA`, `ERITREA`, `DIASPORA` |
| `relationship_intention` | `MARRIAGE`, `SERIOUS_RELATIONSHIP`, `LONG_TERM`, `FRIENDSHIP`, `NOT_SURE_YET` |
| `marital_status` | `NEVER_MARRIED`, `DIVORCED`, `WIDOWED`, `SEPARATED` |
| `education_level` | `HIGH_SCHOOL`, `DIPLOMA`, `BACHELORS`, `MASTERS`, `DOCTORATE`, `OTHER` |
| `ethnicity` | `AMHARA`, `OROMO`, `TIGRINYA`, `SOMALI`, `SIDAMA`, `GURAGE`, `WOLAYTA`, `AFAR`, `HADIYA`, `GAMO`, `OTHER` |
| `nationality` | `ETHIOPIAN`, `ERITREAN`, `DUAL_CITIZEN`, `OTHER` |
| `religion` | `ORTHODOX_CHRISTIAN`, `PROTESTANT`, `CATHOLIC`, `MUSLIM`, `TRADITIONAL`, `OTHER`, `PREFER_NOT_TO_SAY` |
| `smoking_detail` | `NO`, `YES`, `OCCASIONALLY`, `TRYING_TO_QUIT` |
| `drinking_detail` | `NO`, `SOCIALLY`, `OCCASIONALLY`, `YES` |
| `activity_level` | `SEDENTARY`, `LIGHT`, `MODERATE`, `ACTIVE`, `VERY_ACTIVE` |
| `report_type` | `FAKE_PROFILE`, `HARASSMENT`, `INAPPROPRIATE_PHOTO`, `SCAM`, `UNDERAGE`, `OFF_PLATFORM_SOLICITATION`, `OTHER` |
| `relation_status` | `NONE`, `LIKED`, `LIKED_YOU`, `MATCHED` |

---

## Error Codes

| HTTP | Code | When |
|------|------|------|
| 401 | `UNAUTHORIZED` | Missing or invalid Bearer token |
| 403 | `ACCOUNT_SUSPENDED` | User is suspended, banned, or deleted |
| 404 | `PROFILE_NOT_FOUND` | No profile row found, or profile is hidden/blocked |
| 404 | `ADDRESS_NOT_FOUND` | No address linked, or unknown `place_id` |
| 404 | `PREFERENCES_NOT_FOUND` | No discovery_preferences row for user |
| 404 | `PHOTO_NOT_FOUND` | Photo does not exist or is already deleted |
| 404 | `USER_NOT_FOUND` | Target user does not exist or is inactive |
| 404 | `BLOCK_NOT_FOUND` | No active block exists when unblocking |
| 422 | `VALIDATION_ERROR` | Field-level validation failed — check `details` |
| 422 | `PHOTO_LIMIT_EXCEEDED` | Already has 6 active photos |
| 422 | `CANNOT_DELETE_ONLY_PHOTO` | Last approved primary photo on a visible profile |
| 422 | `INVALID_PRIMARY_PHOTO` | Attempted to set PENDING or REJECTED photo as primary |
| 422 | `MISSING_APPROVED_PRIMARY_PHOTO` | Tried to make profile visible with no approved primary photo |
| 422 | `CANNOT_REPORT_SELF` | Reporter and reported are the same user |
| 422 | `CANNOT_BLOCK_SELF` | Blocker and blocked are the same user |
| 500 | `INTERNAL_ERROR` | Unexpected server error |

---

## Endpoints

---

### 1. Get Current User Profile

```
GET /api/v1/profile/me
```

Returns the full profile of the authenticated user, including photos with fresh signed URLs and embedded discovery preferences.

**Request:** no body, no query params.

**Response `200 OK`:**

```ts
interface ProfileMeResponse {
  user_id: string;
  display_name: string;
  age: number;                        // computed server-side from date_of_birth
  gender: string;
  date_of_birth: string;              // ISO date "YYYY-MM-DD"
  bio: string | null;
  height_cm: number | null;
  residency_type: string;
  address: ProfileAddress | null;
  ethnicity: string | null;
  nationality: string | null;
  religion: string | null;
  education_level: string | null;
  occupation: string | null;
  relationship_intention: string;
  marital_status: string | null;
  has_children: boolean;
  wants_children: boolean | null;
  smoking: boolean;                   // derived: true unless smoking_detail is "NO"
  drinking: boolean;                  // derived: true unless drinking_detail is "NO"
  smoking_detail: string | null;
  drinking_detail: string | null;
  activity_level: string | null;
  interests: string[];
  languages: string[];
  is_visible: boolean;
  is_onboarded: boolean;
  is_verified: boolean;
  profile_completion_score: number;   // 0–100
  discovery_preferences: DiscoveryPreferences | null;
  primary_photo_url: string | null;   // signed URL of the primary photo
  photos: ProfilePhoto[];
}
```

**Notes:**
- `smoking` / `drinking` are computed booleans. Prefer `smoking_detail` / `drinking_detail` for display labels.
- `photos` are ordered by `photo_order` ascending.
- Signed URLs expire in 1 hour. Refetch on next screen mount.
- If `discovery_preferences` is `null`, prompt the user to complete onboarding.

**Error responses:** `401`, `403`, `404 PROFILE_NOT_FOUND`

---

### 2. Update Current User Profile

```
PUT /api/v1/profile/me
```

Partial update — only supply the fields you want to change. Omitted fields are left unchanged. Returns the full updated profile (same shape as endpoint 1).

**Request body** (all fields optional):

```ts
interface ProfileUpdateRequest {
  display_name?: string;          // 2–50 chars after trim
  gender?: "MALE" | "FEMALE";
  date_of_birth?: string;         // "YYYY-MM-DD", must be age >= 18
  height_cm?: number;             // 100–250
  residency_type?: string;        // see enum table
  bio?: string;                   // max 2000 chars
  ethnicity?: string;             // see enum table
  nationality?: string;
  religion?: string;
  education_level?: string;
  occupation?: string;            // max 100 chars
  relationship_intention?: string;
  marital_status?: string;
  has_children?: boolean;
  wants_children?: boolean;
  smoking?: boolean;
  drinking?: boolean;
  smoking_detail?: string;        // see enum; sets smoking=true unless "NO"
  drinking_detail?: string;       // see enum; sets drinking=true unless "NO"
  activity_level?: string;
  interests?: string[];           // max 20 items
  languages?: string[];           // max 20 items
}
```

**Response `200 OK`:** `ProfileMeResponse` (same as endpoint 1)

**Notes:**
- `is_onboarded`, `is_verified`, and `profile_completion_score` cannot be set through this endpoint — they are server-controlled.
- Location is managed separately via endpoints 9 and 10.
- `smoking_detail` overrides `smoking` — e.g. sending `smoking_detail: "OCCASIONALLY"` automatically sets `smoking: true`.

**Error responses:** `401`, `403`, `404 PROFILE_NOT_FOUND`, `422 VALIDATION_ERROR`

```json
// 422 example
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "One or more fields are invalid.",
    "details": {
      "date_of_birth": "Must be at least 18 years old.",
      "gender": "must match \"MALE|FEMALE\""
    }
  }
}
```

---

### 3. Get Current User's Photos

```
GET /api/v1/profile/me/photos
```

**Request:** no body, no query params.

**Response `200 OK`:**

```ts
interface ProfilePhotosResponse {
  photos: ProfilePhoto[];
}
```

**Notes:**
- Photos are ordered by `photo_order` ascending (0-indexed).
- Includes photos of all moderation statuses (`PENDING`, `APPROVED`, `REJECTED`).
- All URLs are freshly signed — valid for 1 hour from response time.

**Error responses:** `401`, `403`

---

### 4. Register a New Photo

```
POST /api/v1/profile/me/photos
```

The client uploads the image **directly to Supabase Storage** first, then registers the storage object with this endpoint. The backend creates the `profile_photos` row with `moderation_status = PENDING`.

**Request body:**

```ts
interface PhotoRegistrationRequest {
  storage_bucket: string;   // e.g. "profile-photos"
  storage_path: string;     // e.g. "profile-photos/<userId>/<uuid>.jpg"
  photo_order: number;      // 0–8, suggested position
  is_primary: boolean;      // forced true if this is the user's first photo
}
```

**Response `201 Created`:** `ProfilePhoto`

**Notes:**
- Max 6 active photos. Returns `422 PHOTO_LIMIT_EXCEEDED` if already at the limit.
- If this is the user's first photo, `is_primary` is forced to `true` regardless of the request value.
- `moderation_status` will be `PENDING` immediately after registration. The photo becomes eligible to be primary only after it is `APPROVED`.

**Error responses:** `401`, `403`, `422 PHOTO_LIMIT_EXCEEDED`, `422 VALIDATION_ERROR`

---

### 5. Reorder / Set Primary Photo

```
PUT /api/v1/profile/me/photos
```

Replaces the order and primary flag of all active photos in one atomic operation. The request must include every active photo by ID.

**Request body:**

```ts
interface PhotoReorderRequest {
  photos: Array<{
    id: string;          // UUID of the photo
    photo_order: number; // desired position
    is_primary: boolean;
  }>;
}
```

**Response `200 OK`:** `ProfilePhotosResponse` (same as endpoint 3)

**Normalisation rules applied server-side:**
- Photos are sorted by `photo_order` and reassigned contiguous `0..N-1` values.
- If no photo is marked `is_primary: true`, the first photo becomes primary.
- If multiple photos are marked primary, only the lowest-ordered one stays primary.
- Only `APPROVED` photos can be set as primary — a `PENDING` or `REJECTED` photo with `is_primary: true` returns `422 INVALID_PRIMARY_PHOTO`.

**Error responses:** `401`, `403`, `422 INVALID_PRIMARY_PHOTO`, `422 VALIDATION_ERROR`

---

### 6. Delete a Photo

```
DELETE /api/v1/profile/me/photos/{photoId}
```

**Path parameter:**

| Param | Type | Description |
|-------|------|-------------|
| `photoId` | UUID | ID of the photo to delete |

**Request:** no body.

**Response `200 OK`:** `ProfilePhotosResponse` — the remaining active photos after deletion.

**Notes:**
- Soft-delete only (`deleted_at = NOW()`). The storage object is not removed.
- If the deleted photo was primary, the next surviving photo (lowest `photo_order`) is automatically promoted to primary.
- If the user's profile is visible (`is_visible: true`) and deletion would leave no `APPROVED` primary photo, the request is rejected with `422 CANNOT_DELETE_ONLY_PHOTO`.

**Error responses:** `401`, `403`, `404 PHOTO_NOT_FOUND`, `422 CANNOT_DELETE_ONLY_PHOTO`

---

### 7. Get Discovery Preferences

```
GET /api/v1/profile/me/preferences
```

Alias for `GET /api/v1/discovery/preferences`. Returns the same data from the same DB row.

**Request:** no body, no query params.

**Response `200 OK`:** `DiscoveryPreferences`

```json
{
  "discovery_mode": "PUBLIC",
  "interested_in_gender": "FEMALE",
  "min_age": 22,
  "max_age": 35,
  "max_distance_km": 50,
  "preferred_residency_types": ["ETHIOPIA", "ERITREA", "DIASPORA"],
  "open_to_long_distance": false,
  "open_to_relocation": false,
  "show_verified_only": false
}
```

**Error responses:** `401`, `403`, `404 PREFERENCES_NOT_FOUND`

---

### 8. Update Discovery Preferences

```
PUT /api/v1/profile/me/preferences
```

Alias for `PUT /api/v1/discovery/preferences`. Same validation, same DB row.

**Request body:** `DiscoveryPreferences` (all fields required)

```ts
interface DiscoveryPreferencesUpdateRequest {
  discovery_mode: "PUBLIC" | "INCOGNITO";
  interested_in_gender: "MALE" | "FEMALE";
  min_age: number;                              // 18–120
  max_age: number;                              // 18–120, must be >= min_age
  max_distance_km: number;                      // 1–500
  preferred_residency_types: string[];          // non-empty subset of enum values
  open_to_long_distance: boolean;
  open_to_relocation: boolean;
  show_verified_only: boolean;
}
```

**Response `200 OK`:** `DiscoveryPreferences`

**Notes:**
- `discovery_mode: "PUBLIC"` is stored as `STANDARD` in the DB and returned as `PUBLIC`. Never send `STANDARD` or `GLOBAL` from the client.
- `preferred_residency_types` must be non-empty.
- `min_age` must be <= `max_age`; both must be between 18 and 120.

**Error responses:** `401`, `403`, `422 VALIDATION_ERROR`

---

### 9. Get Current User's Location

```
GET /api/v1/profile/location
```

**Request:** no body, no query params.

**Response `200 OK`:**

```ts
interface ProfileLocationResponse {
  location_source: "GPS" | "MANUAL";
  display_name: string | null;
  city: string | null;
  region: string | null;
  country_code: string | null;
  country_name: string | null;
  formatted_address: string | null;
  place_id: string | null;            // UUID; non-null only for MANUAL locations
  location_precision: string | null;
}
```

**Error responses:** `401`, `403`, `404 ADDRESS_NOT_FOUND`

---

### 10. Update Current User's Location

```
PUT /api/v1/profile/location
```

Accepts either a GPS or MANUAL location. The `location_source` field determines which fields are required.

**GPS request body:**

```ts
{
  location_source: "GPS";
  latitude: number;
  longitude: number;
  country_code?: string;
  country_name?: string;
  city?: string;
  region?: string;
  formatted_address?: string;
}
```

**MANUAL request body:**

```ts
{
  location_source: "MANUAL";
  place_id: string;   // UUID from the location_places table
}
```

**Response `200 OK`:** `ProfileLocationResponse` (same shape as endpoint 9)

**Notes:**
- For GPS: a new `addresses` row is inserted and linked to the user. Exact coordinates are never returned to the client.
- For MANUAL: the backend looks up `location_places` by `place_id` and copies the trusted centroid and address fields. Returns `422 ADDRESS_NOT_FOUND` if `place_id` is not found.
- After a location update, profile and discovery reads reflect the new location immediately.

**Error responses:** `401`, `403`, `422 ADDRESS_NOT_FOUND`, `422 VALIDATION_ERROR`

---

### 11. Get Another User's Profile

```
GET /api/v1/profile/{userId}
```

Returns the public-facing profile of another user. Used by the *Other User Profile* screen.

**Path parameter:**

| Param | Type | Description |
|-------|------|-------------|
| `userId` | UUID | ID of the user whose profile to view |

**Request:** no body.

**Response `200 OK`:**

```ts
interface OtherUserProfileResponse {
  user_id: string;
  display_name: string;
  age: number | null;
  gender: string;
  bio: string | null;
  height_cm: number | null;
  residency_type: string;
  address: ProfileAddress | null;
  ethnicity: string | null;
  nationality: string | null;
  religion: string | null;
  education_level: string | null;
  occupation: string | null;
  relationship_intention: string;
  marital_status: string | null;
  has_children: boolean | null;
  wants_children: boolean | null;
  activity_level: string | null;
  interests: string[];
  languages: string[];
  is_verified: boolean;
  primary_photo_url: string | null;
  photos: ProfilePhoto[];             // APPROVED photos only
  relation_status: "NONE" | "LIKED" | "LIKED_YOU" | "MATCHED";
}
```

**`relation_status` and suggested UI:**

| Value | Meaning | Suggested action button |
|-------|---------|------------------------|
| `NONE` | No interaction yet | — |
| `LIKED` | Caller has liked this user | "Withdraw Like" |
| `LIKED_YOU` | This user has liked the caller | "Decline" |
| `MATCHED` | Mutual match is active | "Unmatch" |

> Unmatch / withdraw like / decline are handled by the discovery/matches API — not this endpoint.

**Notes:**
- Returns `404 PROFILE_NOT_FOUND` if the profile does not exist, `is_visible` is false, the caller has blocked the target, or the target has blocked the caller.
- Only `APPROVED` photos are included. `smoking_detail` and `drinking_detail` are intentionally excluded.
- Signed URL rules are the same as for the current user's photos (1-hour TTL).

**Error responses:** `401`, `403`, `404 PROFILE_NOT_FOUND`

---

### 12. Toggle Profile Visibility

```
PATCH /api/v1/profile/me/visibility
```

Shows or hides the authenticated user's profile in discovery.

**Request body:**

```ts
{
  is_visible: boolean;
}
```

**Response `200 OK`:**

```ts
{
  is_visible: boolean;
  profile_completion_score: number;
}
```

**Notes:**
- Setting `is_visible: true` requires at least one `APPROVED` primary photo. Returns `422 MISSING_APPROVED_PRIMARY_PHOTO` if not met.
- Setting `is_visible: false` always succeeds for an active user.
- The Status tab visibility toggle in the Edit Profile flow should call this endpoint.

**Error responses:** `401`, `403`, `404 PROFILE_NOT_FOUND`, `422 MISSING_APPROVED_PRIMARY_PHOTO`

---

### 13. Report a User

```
POST /api/v1/users/{userId}/report
```

**Path parameter:**

| Param | Type | Description |
|-------|------|-------------|
| `userId` | UUID | ID of the user to report |

**Request body:**

```ts
interface ReportRequest {
  report_type: "FAKE_PROFILE" | "HARASSMENT" | "INAPPROPRIATE_PHOTO"
             | "SCAM" | "UNDERAGE" | "OFF_PLATFORM_SOLICITATION" | "OTHER";
  description?: string;   // max 2000 chars; recommended for "OTHER"
}
```

**Response `201 Created`:**

```ts
interface ReportResponse {
  id: string;               // UUID of the created report
  reported_user_id: string;
  report_type: string;
  description: string | null;
  status: "PENDING";
  created_at: string;       // ISO-8601 UTC
}
```

**Notes:**
- `report_type` is required.
- Multiple reports against the same user are allowed (each creates a separate record for admin review).
- Reports are not visible to the reported user.

**Error responses:** `401`, `403`, `404 USER_NOT_FOUND`, `422 CANNOT_REPORT_SELF`, `422 VALIDATION_ERROR`

---

### 14. Block a User

```
POST /api/v1/users/{userId}/block
```

**Path parameter:**

| Param | Type | Description |
|-------|------|-------------|
| `userId` | UUID | ID of the user to block |

**Request body** (optional — send `{}` or omit body):

```ts
interface BlockRequest {
  reason?: string;   // max 500 chars; for internal reference only
}
```

**Response `201 Created`:**

```ts
interface BlockResponse {
  id: string;               // UUID of the block record
  blocked_user_id: string;
  status: "ACTIVE";
  reason: string | null;
  blocked_at: string;       // ISO-8601 UTC
}
```

**Notes:**
- **Idempotent:** if an active block already exists from the caller to the target, the existing block is returned (no duplicate created).
- Blocking a matched user automatically ends any active match (`end_reason = "BLOCKED"`).
- Both parties become invisible to each other in discovery and via `GET /api/v1/profile/{userId}`.

**Error responses:** `401`, `403`, `404 USER_NOT_FOUND`, `422 CANNOT_BLOCK_SELF`

---

### 15. Unblock a User

```
DELETE /api/v1/users/{userId}/block
```

**Path parameter:**

| Param | Type | Description |
|-------|------|-------------|
| `userId` | UUID | ID of the user to unblock |

**Request:** no body.

**Response `204 No Content`**

**Notes:**
- Only revokes a block **created by the caller**. A user cannot unblock themselves from someone else's block of them.
- Returns `404 BLOCK_NOT_FOUND` if no active block exists from the caller to the target.
- After unblocking, both users may reappear in each other's discovery (subject to normal filters).

**Error responses:** `401`, `403`, `404 BLOCK_NOT_FOUND`

---

## Profile Completion Score

The server computes a score from **23 segments** (each worth 1 point) after every profile edit and photo change.

| Segment | Filled when |
|---------|-------------|
| `display_name` | non-empty |
| `gender` | set |
| `date_of_birth` | set and age >= 18 |
| `height_cm` | set |
| `residency_type` | set |
| `address` | location has been saved |
| `bio` | non-empty |
| `ethnicity` | set |
| `nationality` | set |
| `religion` | set |
| `education_level` | set |
| `occupation` | set |
| `relationship_intention` | set (always — DB NOT NULL) |
| `marital_status` | set |
| `has_children` | set (always — DB default) |
| `wants_children` | set |
| `smoking_detail` | set |
| `drinking_detail` | set |
| `activity_level` | set |
| `interests` | array non-empty |
| `languages` | array non-empty |
| `preferences` | discovery preferences row exists |
| `photos` | at least one active photo |

```
score = round((filled_segments / 23) * 100)
```

**Minimum possible score** (only mandatory DB fields, no address / photos / preferences): 6/23 ≈ **26%**

Use `profile_completion_score` from `GET /api/v1/profile/me` directly for the progress bar — no client-side recalculation required.

---

## Suggested Hook Mapping

```ts
useCurrentProfile()          → GET    /api/v1/profile/me
useUpdateProfile()           → PUT    /api/v1/profile/me
useProfilePhotos()           → GET    /api/v1/profile/me/photos
useRegisterPhoto()           → POST   /api/v1/profile/me/photos
useReorderPhotos()           → PUT    /api/v1/profile/me/photos
useDeletePhoto(photoId)      → DELETE /api/v1/profile/me/photos/{photoId}
useDiscoveryPreferences()    → GET    /api/v1/profile/me/preferences
useUpdatePreferences()       → PUT    /api/v1/profile/me/preferences
useProfileLocation()         → GET    /api/v1/profile/location
useUpdateLocation()          → PUT    /api/v1/profile/location
useOtherUserProfile(userId)  → GET    /api/v1/profile/{userId}
useUpdateVisibility()        → PATCH  /api/v1/profile/me/visibility
useReportUser(userId)        → POST   /api/v1/users/{userId}/report
useBlockUser(userId)         → POST   /api/v1/users/{userId}/block
useUnblockUser(userId)       → DELETE /api/v1/users/{userId}/block
```
