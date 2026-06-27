# Qaliye — Profile & Edit Profile API Specification

**Version:** 1.0  
**Date:** 2025-06-24  
**Base path:** `/api/v1/profile`  
**Authentication:** Supabase JWT Bearer token in the `Authorization` header. The `sub` claim maps to `app_users.id`.

**Source of truth (frontend):**

- `src/screens/profile/CurrentUserProfileScreen.tsx` and `src/screens/profile/components/*`
- `src/screens/profile/EditProfileScreen.tsx` and `src/screens/profile/edit/*`
- `src/types/api.ts` (existing payload/response shapes)
- `src/types/discovery.ts` (discovery preferences DTOs)
- `src/api/profileApi.ts` and `src/api/locationsApi.ts`
- `docs/schema.sql` (database schema)

**Naming convention:** All JSON keys use `snake_case`. Configure Jackson with `PropertyNamingStrategies.SNAKE_CASE` (or annotate with `@JsonProperty`) so the same Java DTOs can be used for requests and responses.

---

## 1. Endpoint List

| # | Method | Path | Description |
|---|--------|------|-------------|
| 1 | `GET`  | `/api/v1/profile/me` | Full current-user profile (read-only) |
| 2 | `PUT`  | `/api/v1/profile/me` | Update editable profile fields |
| 3 | `GET`  | `/api/v1/profile/me/photos` | Current user's profile photos |
| 4 | `POST` | `/api/v1/profile/me/photos` | Register a new photo after client upload |
| 5 | `PUT`  | `/api/v1/profile/me/photos` | Reorder / set primary photo |
| 6 | `DELETE` | `/api/v1/profile/me/photos/{photoId}` | Soft-delete a photo |
| 7 | `GET`  | `/api/v1/profile/me/preferences` | Current user's discovery preferences |
| 8 | `PUT`  | `/api/v1/profile/me/preferences` | Update discovery preferences |
| 9 | `GET`  | `/api/v1/profile/location` | Current user's address/location |
| 10 | `PUT` | `/api/v1/profile/location` | Update address/location (GPS or manual) |

> **Note:** Endpoints 7 and 8 are aliases around the same data as `/api/v1/discovery/preferences`. They exist so the Profile/Edit Profile screens can talk to a single `profile` domain. Implement them in the same transaction/service as the discovery preferences endpoints to avoid divergence.

---

## 2. Request and Response JSON Examples

### 2.1 GET `/api/v1/profile/me`

Returns the full profile needed by the *Current User Profile* screen and the *Edit Profile* screen. All fields are server-side authoritative.

**Response `200 OK`:**

```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "display_name": "Liam",
  "age": 27,
  "gender": "MALE",
  "date_of_birth": "1998-06-12",
  "bio": "Product designer who loves solving problems and creating beautiful experiences.",
  "height_cm": 180,
  "residency_type": "ETHIOPIA",
  "address": {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "city": "Addis Ababa",
    "region": "Addis Ababa",
    "country_code": "ET",
    "country_name": "Ethiopia",
    "formatted_address": "Bole, Addis Ababa, Ethiopia",
    "location_source": "GPS"
  },
  "ethnicity": "TIGRINYA",
  "nationality": "ETHIOPIAN",
  "religion": "ORTHODOX_CHRISTIAN",
  "education_level": "MASTERS",
  "occupation": "Product Designer",
  "relationship_intention": "SERIOUS_RELATIONSHIP",
  "marital_status": "NEVER_MARRIED",
  "has_children": false,
  "wants_children": true,
  "smoking": false,
  "drinking": false,
  "smoking_detail": "NO",
  "drinking_detail": "NO",
  "activity_level": "MODERATE",
  "interests": ["Travel", "Coffee", "Reading", "Fitness", "Music"],
  "languages": ["Amharic", "English", "Oromo"],
  "is_visible": true,
  "is_onboarded": true,
  "is_verified": true,
  "profile_completion_score": 92,
  "discovery_preferences": {
    "discovery_mode": "PUBLIC",
    "interested_in_gender": "FEMALE",
    "min_age": 22,
    "max_age": 35,
    "max_distance_km": 50,
    "preferred_residency_types": ["ETHIOPIA", "ERITREA", "DIASPORA"],
    "open_to_long_distance": false,
    "open_to_relocation": false,
    "show_verified_only": false
  },
  "primary_photo_url": "https://supabase-project.supabase.co/storage/v1/object/sign/profile-photos/...",
  "photos": [
    {
      "id": "p1",
      "photo_order": 0,
      "is_primary": true,
      "signed_url": "https://supabase-project.supabase.co/storage/v1/object/sign/profile-photos/...",
      "expires_at": "2025-06-24T14:00:00Z",
      "moderation_status": "APPROVED"
    }
  ]
}
```

### 2.2 PUT `/api/v1/profile/me`

All fields are optional. Only supplied fields are updated. The server recomputes `profile_completion_score` and `age` after the update and returns the full updated profile (same shape as `GET /api/v1/profile/me`).

**Request body:**

```json
{
  "display_name": "Selam Tesfaye",
  "gender": "FEMALE",
  "date_of_birth": "1995-11-14",
  "height_cm": 165,
  "residency_type": "ETHIOPIA",
  "bio": "Coffee lover, travel enthusiast and believer in meaningful conversations.",
  "ethnicity": "OROMO",
  "nationality": "ETHIOPIAN",
  "religion": "ORTHODOX_CHRISTIAN",
  "education_level": "BACHELORS",
  "occupation": "Software Engineer",
  "relationship_intention": "LONG_TERM",
  "marital_status": "NEVER_MARRIED",
  "has_children": false,
  "wants_children": true,
  "smoking": false,
  "drinking": true,
  "smoking_detail": "NO",
  "drinking_detail": "SOCIALLY",
  "activity_level": "MODERATE",
  "interests": ["Travel", "Coffee", "Reading", "Fitness", "Music"],
  "languages": ["Amharic", "English", "Oromo"]
}
```

**Response `200 OK`:** full `ProfileMeDto` (see 2.1).

### 2.3 GET `/api/v1/profile/me/photos`

**Response `200 OK`:**

```json
{
  "photos": [
    {
      "id": "p1",
      "photo_order": 0,
      "is_primary": true,
      "signed_url": "https://supabase-project.supabase.co/storage/v1/object/sign/profile-photos/...",
      "expires_at": "2025-06-24T14:00:00Z",
      "moderation_status": "APPROVED"
    },
    {
      "id": "p2",
      "photo_order": 1,
      "is_primary": false,
      "signed_url": "https://supabase-project.supabase.co/storage/v1/object/sign/profile-photos/...",
      "expires_at": "2025-06-24T14:00:00Z",
      "moderation_status": "APPROVED"
    }
  ]
}
```

### 2.4 POST `/api/v1/profile/me/photos`

The client uploads the raw image to Supabase Storage directly, then registers the new object with the backend. The backend creates a `profile_photos` row in `PENDING` moderation status and returns the created photo with a fresh signed URL.

**Request body:**

```json
{
  "storage_bucket": "profile-photos",
  "storage_path": "profile-photos/550e8400-.../a1b2c3d4-....jpg",
  "photo_order": 2,
  "is_primary": false
}
```

**Response `201 Created`:**

```json
{
  "id": "p3",
  "photo_order": 2,
  "is_primary": false,
  "signed_url": "https://supabase-project.supabase.co/storage/v1/object/sign/profile-photos/...",
  "expires_at": "2025-06-24T14:00:00Z",
  "moderation_status": "PENDING"
}
```

### 2.5 PUT `/api/v1/profile/me/photos`

Updates the order and primary flag of existing photos. The array must contain every active photo (ids). The server normalises the order to `0..N-1` and ensures exactly one primary photo.

**Request body:**

```json
{
  "photos": [
    { "id": "p2", "photo_order": 0, "is_primary": true },
    { "id": "p1", "photo_order": 1, "is_primary": false }
  ]
}
```

**Response `200 OK`:** same shape as `GET /api/v1/profile/me/photos`.

### 2.6 DELETE `/api/v1/profile/me/photos/{photoId}`

Soft-deletes the photo (`deleted_at = NOW()`). If the deleted photo was primary, the backend promotes the next surviving photo. If the deletion would leave a visible profile without an approved primary photo, the request is rejected.

**Response `200 OK`:** same shape as `GET /api/v1/profile/me/photos`.

### 2.7 GET `/api/v1/profile/me/preferences`

**Response `200 OK`:**

```json
{
  "discovery_mode": "PUBLIC",
  "interested_in_gender": "MALE",
  "min_age": 24,
  "max_age": 34,
  "max_distance_km": 50,
  "preferred_residency_types": ["ETHIOPIA", "DIASPORA"],
  "open_to_long_distance": true,
  "open_to_relocation": false,
  "show_verified_only": true
}
```

### 2.8 PUT `/api/v1/profile/me/preferences`

Same payload and rules as `PUT /api/v1/discovery/preferences`.

**Request body:**

```json
{
  "discovery_mode": "PUBLIC",
  "interested_in_gender": "MALE",
  "min_age": 24,
  "max_age": 34,
  "max_distance_km": 50,
  "preferred_residency_types": ["ETHIOPIA", "DIASPORA"],
  "open_to_long_distance": true,
  "open_to_relocation": false,
  "show_verified_only": true
}
```

**Response `200 OK`:** same shape as `GET /api/v1/profile/me/preferences`.

### 2.9 GET `/api/v1/profile/location`

**Response `200 OK`:**

```json
{
  "location_source": "GPS",
  "display_name": "Addis Ababa, Ethiopia",
  "city": "Addis Ababa",
  "region": "Addis Ababa",
  "country_code": "ET",
  "country_name": "Ethiopia",
  "formatted_address": "Bole, Addis Ababa, Ethiopia",
  "place_id": null,
  "location_precision": "GPS"
}
```

### 2.10 PUT `/api/v1/profile/location`

**GPS request body:**

```json
{
  "location_source": "GPS",
  "latitude": 9.145,
  "longitude": 40.489,
  "country_code": "ET",
  "country_name": "Ethiopia",
  "city": "Addis Ababa",
  "region": "Addis Ababa",
  "formatted_address": "Bole, Addis Ababa, Ethiopia"
}
```

**Manual request body:**

```json
{
  "location_source": "MANUAL",
  "place_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

**Response `200 OK`:** same shape as `GET /api/v1/profile/location`.

## 3. DTO Definitions

Package: `com.qaliye.backend.profile.dto` (controller/service may live in `com.qaliye.backend.user` for consistency with the JPA entities). Use `record` DTOs and configure Jackson for `snake_case` serialization.

### 3.1 `ProfileMeDto`

Returned by `GET /api/v1/profile/me` and `PUT /api/v1/profile/me`.

```java
public record ProfileMeDto(
    UUID userId,
    String displayName,
    Integer age,
    String gender,
    LocalDate dateOfBirth,
    @Nullable String bio,
    @Nullable Integer heightCm,
    String residencyType,
    @Nullable ProfileAddressDto address,
    @Nullable String ethnicity,
    @Nullable String nationality,
    @Nullable String religion,
    @Nullable String educationLevel,
    @Nullable String occupation,
    String relationshipIntention,
    @Nullable String maritalStatus,
    Boolean hasChildren,
    @Nullable Boolean wantsChildren,
    Boolean smoking,
    Boolean drinking,
    @Nullable String smokingDetail,
    @Nullable String drinkingDetail,
    @Nullable String activityLevel,
    List<String> interests,
    List<String> languages,
    Boolean isVisible,
    Boolean isOnboarded,
    Boolean isVerified,
    Integer profileCompletionScore,
    DiscoveryPreferencesDto discoveryPreferences,
    @Nullable String primaryPhotoUrl,
    List<ProfilePhotoDto> photos
) {}
```

### 3.2 `ProfileAddressDto`

```java
public record ProfileAddressDto(
    UUID id,
    String city,
    @Nullable String region,
    String countryCode,
    String countryName,
    @Nullable String formattedAddress,
    String locationSource
) {}
```

### 3.3 `ProfilePhotoDto`

```java
public record ProfilePhotoDto(
    UUID id,
    Integer photoOrder,
    Boolean isPrimary,
    String signedUrl,
    Instant expiresAt,
    String moderationStatus
) {}
```

### 3.4 `ProfileUpdateRequest`

Body for `PUT /api/v1/profile/me`. All fields optional. The backend must keep `userId`, `isOnboarded`, `isVerified`, `profileCompletionScore`, and the address untouched.

```java
public record ProfileUpdateRequest(
    @Size(min = 2, max = 50) @Nullable String displayName,
    @Pattern(regexp = "MALE|FEMALE") @Nullable String gender,
    @Nullable LocalDate dateOfBirth,
    @Min(100) @Max(250) @Nullable Integer heightCm,
    @Pattern(regexp = "ETHIOPIA|ERITREA|DIASPORA") @Nullable String residencyType,
    @Size(max = 2000) @Nullable String bio,
    @Nullable String ethnicity,
    @Nullable String nationality,
    @Nullable String religion,
    @Nullable String educationLevel,
    @Size(max = 100) @Nullable String occupation,
    @Pattern(regexp = "MARRIAGE|SERIOUS_RELATIONSHIP|LONG_TERM|FRIENDSHIP|NOT_SURE_YET") @Nullable String relationshipIntention,
    @Nullable String maritalStatus,
    @Nullable Boolean hasChildren,
    @Nullable Boolean wantsChildren,
    @Nullable Boolean smoking,
    @Nullable Boolean drinking,
    @Pattern(regexp = "NO|YES|OCCASIONALLY|TRYING_TO_QUIT") @Nullable String smokingDetail,
    @Pattern(regexp = "NO|SOCIALLY|OCCASIONALLY|YES") @Nullable String drinkingDetail,
    @Pattern(regexp = "SEDENTARY|LIGHT|MODERATE|ACTIVE|VERY_ACTIVE") @Nullable String activityLevel,
    @Size(max = 20) @Nullable List<String> interests,
    @Size(max = 20) @Nullable List<String> languages
) {}
```

**Behaviour note:** If `smokingDetail` is provided, the backend must also set `smoking` to `true` unless `smokingDetail` is `NO`. If `drinkingDetail` is provided, the backend must also set `drinking` to `true` unless `drinkingDetail` is `NO`.

### 3.5 `ProfilePhotosResponse`

```java
public record ProfilePhotosResponse(
    List<ProfilePhotoDto> photos
) {}
```

### 3.6 `PhotoReorderItem` and `PhotoReorderRequest`

```java
public record PhotoReorderItem(
    UUID id,
    Integer photoOrder,
    Boolean isPrimary
) {}

public record PhotoReorderRequest(
    @NotEmpty List<PhotoReorderItem> photos
) {}
```

### 3.7 `PhotoRegistrationRequest`

```java
public record PhotoRegistrationRequest(
    @NotBlank String storageBucket,
    @NotBlank String storagePath,
    @Min(0) @Max(8) Integer photoOrder,
    @NotNull Boolean isPrimary
) {}
```

### 3.8 `DiscoveryPreferencesDto`

Same shape as in `src/types/discovery.ts` and `docs/discovery-api-new.md`.

```java
public record DiscoveryPreferencesDto(
    String discoveryMode,
    String interestedInGender,
    Integer minAge,
    Integer maxAge,
    Integer maxDistanceKm,
    String[] preferredResidencyTypes,
    Boolean openToLongDistance,
    Boolean openToRelocation,
    Boolean showVerifiedOnly
) {}
```

**Discovery mode mapping:** The database stores `STANDARD` / `GLOBAL` / `INCOGNITO`. The API only exposes `PUBLIC` and `INCOGNITO` to the mobile client.

- `PUBLIC` ↔ DB `STANDARD` (and `GLOBAL` is also returned as `PUBLIC`).
- `INCOGNITO` ↔ DB `INCOGNITO`.

### 3.9 `ProfileLocationDto`

```java
public record ProfileLocationDto(
    String locationSource,
    @Nullable String displayName,
    @Nullable String city,
    @Nullable String region,
    @Nullable String countryCode,
    @Nullable String countryName,
    @Nullable String formattedAddress,
    @Nullable String placeId,
    @Nullable String locationPrecision
) {}
```

### 3.10 `GpsLocationRequest`

```java
public record GpsLocationRequest(
    String locationSource, // "GPS"
    Double latitude,
    Double longitude,
    @Nullable String countryCode,
    @Nullable String countryName,
    @Nullable String city,
    @Nullable String region,
    @Nullable String formattedAddress
) {}
```

### 3.11 `ManualLocationRequest`

```java
public record ManualLocationRequest(
    String locationSource, // "MANUAL"
    UUID placeId
) {}
```

### 3.12 `ApiError`

All error responses use the same envelope as the discovery API.

```java
public record ApiError(
    String code,
    String message,
    @Nullable Map<String, Object> details
) {}
```

Example JSON:

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "One or more fields are invalid.",
    "details": {
      "date_of_birth": "Must be at least 18 years old."
    }
  }
}
```

## 4. Service-Layer Business Rules

### 4.1 Actor Eligibility (applies to every profile endpoint)

1. Load `app_users` by JWT `sub`. If `deleted_at IS NOT NULL` or `status != 'ACTIVE'`, return `403 ACCOUNT_SUSPENDED`.
2. The `profiles` row must exist. If it does not, return `404 PROFILE_NOT_FOUND`. (Onboarding guarantees the row is created before the user reaches this screen.)
3. The caller may only read or mutate their own profile. The JWT `sub` is the authoritative user ID.

### 4.2 `ProfileService.getCurrentProfile(userId)`

1. Load `profiles` row joined with `app_users` and `addresses` (via `app_users.address_id`).
2. Compute `age` from `date_of_birth` using the database `calculate_age` function or the SQL equivalent.
3. Load `discovery_preferences` for `userId`. Map `discovery_mode` from DB to API values (see 3.8).
4. Load all `profile_photos` where `deleted_at IS NULL`, ordered by `photo_order`.
5. Generate fresh signed URLs via the Supabase Storage REST API (`POST /storage/v1/object/sign/{bucket}/{path}`) with a 1-hour TTL. Never return raw `storage_path` values.
6. Set `primaryPhotoUrl` to the signed URL of the photo with `is_primary = TRUE` (if any).
7. Return the assembled `ProfileMeDto`.

### 4.3 `ProfileService.updateProfile(userId, request)`

1. Validate actor eligibility (4.1).
2. Validate `date_of_birth`: the caller must be at least 18 years old. If not, return `422 VALIDATION_ERROR` with `details.date_of_birth`.
3. Validate enum/string fields against the accepted values in the table below. If a value is invalid, return `422 VALIDATION_ERROR` with the field name in `details`.
4. Apply only the supplied fields. Do **not** clear `bio`, `height_cm`, etc. when they are omitted.
5. Do **not** allow mutating `is_onboarded`, `is_verified`, or `profile_completion_score` through this endpoint. These are server-controlled.
6. Derive `smoking` and `drinking` booleans from `smoking_detail` / `drinking_detail` when provided (see 3.4).
7. After updating the `profiles` row, recompute `profile_completion_score` (see 5.2) and update it in the same transaction.
8. Return the full `ProfileMeDto`.

| Field | Accepted values / constraints |
|-------|-------------------------------|
| `gender` | `MALE`, `FEMALE` |
| `residency_type` | `ETHIOPIA`, `ERITREA`, `DIASPORA` |
| `date_of_birth` | ISO date, age ≥ 18 |
| `height_cm` | 100–250 |
| `relationship_intention` | `MARRIAGE`, `SERIOUS_RELATIONSHIP`, `LONG_TERM`, `FRIENDSHIP`, `NOT_SURE_YET` |
| `marital_status` | `NEVER_MARRIED`, `DIVORCED`, `WIDOWED`, `SEPARATED` (or null) |
| `education_level` | `HIGH_SCHOOL`, `DIPLOMA`, `BACHELORS`, `MASTERS`, `DOCTORATE`, `OTHER` (or null) |
| `ethnicity` | `AMHARA`, `OROMO`, `TIGRINYA`, `SOMALI`, `SIDAMA`, `GURAGE`, `WOLAYTA`, `AFAR`, `HADIYA`, `GAMO`, `OTHER` (or null) |
| `nationality` | `ETHIOPIAN`, `ERITREAN`, `DUAL_CITIZEN`, `OTHER` (or null) |
| `religion` | `ORTHODOX_CHRISTIAN`, `PROTESTANT`, `CATHOLIC`, `MUSLIM`, `TRADITIONAL`, `OTHER`, `PREFER_NOT_TO_SAY` (or null) |
| `smoking_detail` | `NO`, `YES`, `OCCASIONALLY`, `TRYING_TO_QUIT` (or null) |
| `drinking_detail` | `NO`, `SOCIALLY`, `OCCASIONALLY`, `YES` (or null) |
| `activity_level` | `SEDENTARY`, `LIGHT`, `MODERATE`, `ACTIVE`, `VERY_ACTIVE` (or null) |
| `interests` | max 20 strings; max 50 chars each |
| `languages` | max 20 strings; max 50 chars each |
| `display_name` | 2–50 characters after trimming |
| `bio` | max 2000 characters |
| `occupation` | max 100 characters |

### 4.4 Photo Management Rules

1. Max 6 active photos per user (`profile_photos.deleted_at IS NULL`). Reject `POST /api/v1/profile/me/photos` with `422 PHOTO_LIMIT_EXCEEDED` if the user already has 6 active photos.
2. Exactly one active photo must be primary. The `PUT /api/v1/profile/me/photos` endpoint normalises the input:
    - Reorder the photos by the supplied `photo_order`, then assign contiguous `0..N-1` order values.
    - If no photo is marked primary, set the first photo as primary.
    - If multiple photos are marked primary, keep only the lowest-ordered one as primary and set the rest to `is_primary = FALSE`.
3. `DELETE /api/v1/profile/me/photos/{photoId}`:
    - Verify the photo belongs to the caller.
    - Soft-delete (`deleted_at = NOW()`).
    - If the deleted photo was primary, promote the surviving photo with the lowest `photo_order` to primary.
    - If the caller has an active profile (`is_visible = TRUE`) and, after deletion, there is no `APPROVED` primary photo, return `422 CANNOT_DELETE_ONLY_PHOTO`. Do not rely on the DB trigger to raise the error; validate explicitly for a clear error message.
4. `POST /api/v1/profile/me/photos` creates the row with `moderation_status = 'PENDING'`. If it is the first photo for the user, force `is_primary = TRUE` regardless of the request value.
5. Approved photos only are eligible to be primary. The `PUT /api/v1/profile/me/photos` endpoint must reject an attempt to set a `REJECTED` or `PENDING` photo as primary (return `422 INVALID_PRIMARY_PHOTO`).

### 4.5 Discovery Preferences Rules

1. `GET /api/v1/profile/me/preferences` reads the same row as `GET /api/v1/discovery/preferences`. If the row does not exist, return `404 PREFERENCES_NOT_FOUND`.
2. `PUT /api/v1/profile/me/preferences` validates the same constraints as `PUT /api/v1/discovery/preferences`:
    - `interested_in_gender`: `MALE` or `FEMALE`.
    - `min_age`: 18–120, `max_age`: 18–120, `min_age <= max_age`.
    - `max_distance_km`: 1–500.
    - `preferred_residency_types`: non-empty subset of `ETHIOPIA`, `ERITREA`, `DIASPORA`.
    - `discovery_mode`: `PUBLIC` or `INCOGNITO`. Persist as `STANDARD` for `PUBLIC` and `INCOGNITO` for `INCOGNITO`.
3. Reuse the same service method as the discovery preferences endpoint to avoid duplicate logic.

### 4.6 Location Update Rules

1. `GET /api/v1/profile/location` returns the current `addresses` row linked by `app_users.address_id`. If no address is linked, return `404 ADDRESS_NOT_FOUND`.
2. `PUT /api/v1/profile/location` with `location_source = "GPS"`:
    - Upsert an `addresses` row with the provided fields and `coords = ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)`.
    - Update `app_users.address_id` to the new/updated address ID.
3. `PUT /api/v1/profile/location` with `location_source = "MANUAL"`:
    - Look up `location_places` by `place_id`. If not found, return `422 ADDRESS_NOT_FOUND`.
    - Copy `city`, `region`, `country_code`, `country_name`, `display_name`, and the trusted centroid `coords` from `location_places` to a new `addresses` row.
    - Update `app_users.address_id` to the new address ID.
4. After changing the address, discovery and profile reads reflect the new location immediately. The `addresses.updated_at` trigger is handled by the database.

### 4.7 Signed URL Rules

- All photos live in private Supabase Storage. The backend must call Supabase Storage to generate signed URLs (TTL 3600 seconds) and return them to the client. Never expose raw `storage_path` values.
- The backend does not cache signed URLs. Each read generates fresh URLs.
- The `expires_at` field in the response is the absolute expiry time of `signed_url`.

## 5. Database Access and Query Strategy

### 5.1 Read Current Profile

```sql
SELECT
    p.user_id,
    p.display_name,
    p.gender,
    p.date_of_birth,
    calculate_age(p.date_of_birth) AS age,
    p.bio,
    p.height_cm,
    p.residency_type,
    p.ethnicity,
    p.nationality,
    p.religion,
    p.education_level,
    p.occupation,
    p.relationship_intention,
    p.marital_status,
    p.has_children,
    p.wants_children,
    p.smoking,
    p.drinking,
    p.smoking_detail,
    p.drinking_detail,
    p.activity_level,
    p.interests,
    p.languages,
    p.is_visible,
    p.is_onboarded,
    p.is_verified,
    p.profile_completion_score,
    a.id AS address_id,
    a.city,
    a.region,
    a.country_code,
    a.country_name,
    a.formatted_address,
    a.location_source,
    au.last_active_at
FROM profiles p
JOIN app_users au ON au.id = p.user_id
LEFT JOIN addresses a ON a.id = au.address_id
WHERE p.user_id = :userId;
```

### 5.2 Read Discovery Preferences

```sql
SELECT
    discovery_mode,
    interested_in_gender,
    min_age,
    max_age,
    max_distance_km,
    preferred_residency_types,
    open_to_long_distance,
    open_to_relocation,
    show_verified_only
FROM discovery_preferences
WHERE user_id = :userId;
```

### 5.3 Read Photos

```sql
SELECT
    id,
    user_id,
    storage_bucket,
    storage_path,
    photo_order,
    is_primary,
    moderation_status,
    created_at
FROM profile_photos
WHERE user_id = :userId
  AND deleted_at IS NULL
ORDER BY photo_order ASC;
```

### 5.4 Update Profile Fields

```sql
UPDATE profiles
SET
    display_name = COALESCE(:displayName, display_name),
    gender = COALESCE(:gender, gender),
    date_of_birth = COALESCE(:dateOfBirth, date_of_birth),
    height_cm = COALESCE(:heightCm, height_cm),
    residency_type = COALESCE(:residencyType, residency_type),
    bio = COALESCE(:bio, bio),
    ethnicity = COALESCE(:ethnicity, ethnicity),
    nationality = COALESCE(:nationality, nationality),
    religion = COALESCE(:religion, religion),
    education_level = COALESCE(:educationLevel, education_level),
    occupation = COALESCE(:occupation, occupation),
    relationship_intention = COALESCE(:relationshipIntention, relationship_intention),
    marital_status = COALESCE(:maritalStatus, marital_status),
    has_children = COALESCE(:hasChildren, has_children),
    wants_children = COALESCE(:wantsChildren, wants_children),
    smoking = COALESCE(:smoking, smoking),
    drinking = COALESCE(:drinking, drinking),
    smoking_detail = COALESCE(:smokingDetail, smoking_detail),
    drinking_detail = COALESCE(:drinkingDetail, drinking_detail),
    activity_level = COALESCE(:activityLevel, activity_level),
    interests = COALESCE(:interests, interests),
    languages = COALESCE(:languages, languages),
    profile_completion_score = :newScore
WHERE user_id = :userId;
```

The `updated_at` trigger handles the timestamp. `profile_completion_score` is recomputed by Java before issuing the query.

### 5.5 Photo Ordering Update

```sql
UPDATE profile_photos
SET photo_order = :photoOrder,
    is_primary = :isPrimary,
    updated_at = NOW()
WHERE id = :photoId
  AND user_id = :userId
  AND deleted_at IS NULL;
```

Run this for each item in the request, then run a single guard query to enforce exactly one primary:

```sql
-- If no active photo is primary, promote the lowest-ordered surviving photo
UPDATE profile_photos
SET is_primary = TRUE
WHERE id = (
    SELECT id FROM profile_photos
    WHERE user_id = :userId AND deleted_at IS NULL
    ORDER BY photo_order ASC
    LIMIT 1
)
  AND NOT EXISTS (
      SELECT 1 FROM profile_photos
      WHERE user_id = :userId AND deleted_at IS NULL AND is_primary = TRUE
  );
```

### 5.6 Photo Soft-Delete

```sql
UPDATE profile_photos
SET deleted_at = NOW()
WHERE id = :photoId AND user_id = :userId AND deleted_at IS NULL;

-- Promote next primary if needed
UPDATE profile_photos
SET is_primary = TRUE
WHERE id = (
    SELECT id FROM profile_photos
    WHERE user_id = :userId AND deleted_at IS NULL
    ORDER BY photo_order ASC
    LIMIT 1
)
  AND NOT EXISTS (
      SELECT 1 FROM profile_photos
      WHERE user_id = :userId AND deleted_at IS NULL AND is_primary = TRUE
  );
```

### 5.7 Register New Photo

```sql
INSERT INTO profile_photos (
    id,
    user_id,
    storage_bucket,
    storage_path,
    photo_order,
    is_primary,
    moderation_status,
    metadata
) VALUES (
    gen_random_uuid(),
    :userId,
    :storageBucket,
    :storagePath,
    :photoOrder,
    :isPrimary,
    'PENDING',
    '{}'::JSONB
);
```

### 5.8 Location Upsert (GPS)

```sql
INSERT INTO addresses (
    id, country_code, country_name, city, region, formatted_address, location_source, coords
) VALUES (
    gen_random_uuid(), :countryCode, :countryName, :city, :region, :formattedAddress, 'GPS',
    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)
)
ON CONFLICT (id) DO UPDATE SET
    country_code = EXCLUDED.country_code,
    country_name = EXCLUDED.country_name,
    city = EXCLUDED.city,
    region = EXCLUDED.region,
    formatted_address = EXCLUDED.formatted_address,
    location_source = EXCLUDED.location_source,
    coords = EXCLUDED.coords
RETURNING id;

UPDATE app_users
SET address_id = :addressId
WHERE id = :userId;
```

> **Note:** `addresses.id` is generated by `gen_random_uuid()`; there is no unique key other than the PK. For the GPS case, the backend can simply insert a new `addresses` row each time and update `app_users.address_id`, leaving the old row unreferenced (or clean it up later).

### 5.9 Location Insert (Manual)

```sql
INSERT INTO addresses (
    id, country_code, country_name, city, region, formatted_address, location_source, coords, location_place_id
)
SELECT
    gen_random_uuid(),
    country_code,
    country_name,
    city,
    region,
    display_name,
    'MANUAL',
    coords,
    id
FROM location_places
WHERE id = :placeId
RETURNING id;

UPDATE app_users
SET address_id = :addressId
WHERE id = :userId;
```

## 6. Profile Completion Score Algorithm

The server recompute should match the client-side progress bar formula in `src/screens/profile/edit/mockEditProfile.ts`. The algorithm uses 23 weighted segments and returns an integer 0–100.

```text
basics      = 6 segments
personal    = 10 segments
lifestyle   = 5 segments
preferences = 1 segment
photos      = 1 segment
total       = 23
```

A segment counts as filled if its condition is true:

| Segment | Filled when |
|---------|-------------|
| `display_name` | non-empty after trim |
| `gender` | non-null |
| `date_of_birth` | non-null and age ≥ 18 |
| `height_cm` | non-null |
| `residency_type` | non-null |
| `address` | `app_users.address_id` IS NOT NULL |
| `bio` | non-empty after trim |
| `ethnicity` | non-null |
| `nationality` | non-null |
| `religion` | non-null |
| `education_level` | non-null |
| `occupation` | non-null |
| `relationship_intention` | non-null (always, because of DB NOT NULL) |
| `marital_status` | non-null |
| `has_children` | non-null (always, because of DB NOT NULL default) |
| `wants_children` | non-null |
| `smoking_detail` | non-null |
| `drinking_detail` | non-null |
| `activity_level` | non-null |
| `interests` | array is non-empty |
| `languages` | array is non-empty |
| `preferences` | `discovery_preferences` row exists for the user |
| `photos` | at least one active photo exists (`deleted_at IS NULL`) |

```text
score = ROUND((filled_segments / 23) * 100)
```

Store the result in `profiles.profile_completion_score` after every profile update and after every photo mutation that affects the user's profile.

## 7. Error Codes and Messages

All error responses use the envelope from section 3.12.

| HTTP | Code | Trigger |
|------|------|---------|
| `401` | `UNAUTHORIZED` | Missing or invalid Bearer token. |
| `403` | `ACCOUNT_SUSPENDED` | `app_users.status != 'ACTIVE'` or `deleted_at IS NOT NULL`. |
| `404` | `PROFILE_NOT_FOUND` | No `profiles` row for the authenticated user. |
| `404` | `ADDRESS_NOT_FOUND` | `GET /api/v1/profile/location` when no address is linked, or `PUT /api/v1/profile/location` with an unknown `place_id`. |
| `404` | `PREFERENCES_NOT_FOUND` | No `discovery_preferences` row for the user. |
| `404` | `PHOTO_NOT_FOUND` | The requested photo does not exist or is soft-deleted. |
| `422` | `VALIDATION_ERROR` | Generic validation failure. `details` contains per-field messages. |
| `422` | `PHOTO_LIMIT_EXCEEDED` | Attempting to add more than 6 active photos. |
| `422` | `CANNOT_DELETE_ONLY_PHOTO` | Deleting the last approved primary photo while the profile is visible. |
| `422` | `INVALID_PRIMARY_PHOTO` | Attempting to set a non-approved photo as primary. |
| `500` | `INTERNAL_ERROR` | Unexpected server error (e.g., Supabase Storage signing failure). |

## 8. Test Cases

The backend agent should add tests covering the following. Use `@WebMvcTest` for the controller and `@DataJpaTest` / integration tests for the service layer.

1. **Get current profile**
    - Returns 200 with all expected fields, computed age, and signed photo URLs.
    - Returns 404 when the profile row is missing.
2. **Update profile**
    - Valid partial update: only supplied fields change; others remain unchanged.
    - Underage `date_of_birth` returns 422 with `details.date_of_birth`.
    - Invalid enum values (e.g., `gender = "OTHER"`) return 422 `VALIDATION_ERROR`.
    - `smoking_detail = "OCCASIONALLY"` sets `smoking = TRUE`.
    - `smoking_detail = "NO"` sets `smoking = FALSE`.
3. **Profile completion score**
    - Profile with only mandatory fields (no address, photos, preferences, or optional lifestyle fields) returns the minimum score (6/23 ≈ 26%).
    - Fully populated profile (including lifestyle, preferences, photos) returns 100.
    - Updating a single missing field increases the score by the correct increment (1/23 ≈ 4%).
4. **Photo management**
    - Registering a 7th photo returns `422 PHOTO_LIMIT_EXCEEDED`.
    - Reordering sets contiguous `photo_order` and exactly one primary.
    - Deleting the primary promotes the next photo.
    - Deleting the last approved primary while visible returns `422 CANNOT_DELETE_ONLY_PHOTO`.
    - Setting a `REJECTED` photo as primary returns `422 INVALID_PRIMARY_PHOTO`.
5. **Discovery preferences**
    - `PUT /api/v1/profile/me/preferences` with `discovery_mode = "PUBLIC"` persists `STANDARD` in the DB and returns `PUBLIC` on read.
    - Invalid `preferred_residency_types` returns 422.
6. **Location**
    - GPS update inserts an address and links `app_users.address_id`.
    - Manual update with a valid `place_id` copies the `location_places` centroid.
    - Manual update with an invalid `place_id` returns `422 ADDRESS_NOT_FOUND`.

## 9. Schema Amendments

The current `docs/schema.sql` does not include columns for the *Lifestyle* tab fields (`activity_level`, `interests`, `languages`) or the detailed smoking/drinking options (`smoking_detail`, `drinking_detail`). Add the following columns in a new Flyway migration (`db/migration/Vxxx__profile_lifestyle_columns.sql`):

```sql
ALTER TABLE public.profiles
    ADD COLUMN activity_level VARCHAR(50),
    ADD COLUMN interests TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN languages TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN smoking_detail VARCHAR(50),
    ADD COLUMN drinking_detail VARCHAR(50);

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_activity_level CHECK (
        activity_level IS NULL OR activity_level IN (
            'SEDENTARY', 'LIGHT', 'MODERATE', 'ACTIVE', 'VERY_ACTIVE'
        )
    );

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_smoking_detail CHECK (
        smoking_detail IS NULL OR smoking_detail IN (
            'NO', 'YES', 'OCCASIONALLY', 'TRYING_TO_QUIT'
        )
    );

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_drinking_detail CHECK (
        drinking_detail IS NULL OR drinking_detail IN (
            'NO', 'SOCIALLY', 'OCCASIONALLY', 'YES'
        )
    );

ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_lifestyle_array_limits CHECK (
        cardinality(interests) <= 20 AND cardinality(languages) <= 20
    );
```

Also add a `CHECK` constraint on `marital_status` to keep values canonical:

```sql
ALTER TABLE public.profiles
    ADD CONSTRAINT chk_profiles_marital_status CHECK (
        marital_status IS NULL OR marital_status IN (
            'NEVER_MARRIED', 'DIVORCED', 'WIDOWED', 'SEPARATED'
        )
    );
```

## 10. Frontend Integration Notes

These notes are for the client-side agent that will consume the API.

- `src/api/profileApi.ts` should be updated to match the canonical endpoints and DTOs. The existing `BasicProfilePayload` and `ProfileMeResponse` in `src/types/api.ts` are superseded by the shapes in this document.
- **Photo path correction:** `src/api/photosApi.ts` currently calls `POST /api/v1/profile/photos` and `GET /api/v1/profile/photos/me`. Update these to `POST /api/v1/profile/me/photos` and `GET /api/v1/profile/me/photos` to match the canonical paths. Also add `rejection_reason` to the local `ProfilePhoto` interface (it already exists in `photosApi.ts`).
- `src/hooks/profile/` should expose:
    - `useCurrentProfile()` → `GET /api/v1/profile/me`
    - `useUpdateProfile()` → `PUT /api/v1/profile/me`
    - `useProfilePhotos()` → `GET /api/v1/profile/me/photos`
    - `useRegisterPhoto()` → `POST /api/v1/profile/me/photos`
    - `useReorderPhotos()` → `PUT /api/v1/profile/me/photos`
    - `useDeletePhoto()` → `DELETE /api/v1/profile/me/photos/{photoId}`
    - `useProfileLocation()` → `GET /api/v1/profile/location`
    - `useUpdateProfileLocation()` → `PUT /api/v1/profile/location`
    - `useOtherUserProfile(userId)` → `GET /api/v1/profile/{userId}`
    - `useUpdateVisibility()` → `PATCH /api/v1/profile/me/visibility`
    - `useReportUser(userId)` → `POST /api/v1/users/{userId}/report`
    - `useBlockUser(userId)` → `POST /api/v1/users/{userId}/block`
    - `useUnblockUser(userId)` → `DELETE /api/v1/users/{userId}/block`
- The *Edit Profile* screen currently groups the save payload into `{ draft, prefs, photos }`. The client should map that local shape to the three API calls: `PUT /api/v1/profile/me`, `PUT /api/v1/profile/me/preferences`, and `PUT /api/v1/profile/me/photos` (plus `POST /api/v1/profile/me/photos` for newly uploaded images).
- The *Edit Profile* location picker should call `PUT /api/v1/profile/location` when the user selects a new address, not include the address string in the profile update payload.
- The *Other User Profile* screen (`OtherUserProfileScreen.tsx`) gets its data from `GET /api/v1/profile/{userId}`. The `relation_status` field drives the action button label (`Unmatch`, `Withdraw Like`, `Decline`). The actual actions for unmatch / withdraw like / decline belong to the discovery/matches domain and are not part of this spec.
- The Status tab (`StatusContent.tsx`) reads `is_visible`, `is_onboarded`, `is_verified`, and `profile_completion_score` from `GET /api/v1/profile/me`. The visibility toggle in the Edit Profile flow should call `PATCH /api/v1/profile/me/visibility`.
- Report and Block actions triggered from the `OtherUserProfileScreen` menu should call endpoints 13 and 14 respectively (`POST /api/v1/users/{userId}/report` and `POST /api/v1/users/{userId}/block`).
- Display labels for enum values (e.g., `ORTHODOX_CHRISTIAN` → "Orthodox Christian") are the responsibility of the client / i18n layer. The backend only accepts and returns the canonical enum values listed in section 4.3.
- Signed photo URLs expire in 1 hour. The client should refresh the profile/photos when URLs are about to expire, or simply rely on the next screen mount to fetch fresh URLs.
- `discovery_mode` returned by this API is `PUBLIC` or `INCOGNITO`. The client should not send `STANDARD` or `GLOBAL` to this API.

---

## 11. Deliverables for the Backend Agent

1. `ProfileController.java` under `com.qaliye.backend.user` (or `com.qaliye.backend.profile`) implementing **endpoints 1–12** (profile CRUD, photos, preferences, location, other user profile, visibility toggle).
2. `UserSocialController.java` under `com.qaliye.backend.user` implementing **endpoints 13–15** (report, block, unblock). Note: `user_reports` and `user_blocks` tables already exist in `docs/schema.sql`; no new migrations required for these tables.
3. `ProfileService.java` and `ProfilePhotoService.java` with the business rules from section 4.
4. `UserSocialService.java` for report and block logic (section 4.10–4.11).
5. Java DTO records in `com.qaliye.backend.profile.dto` (or `com.qaliye.backend.user.dto`) including `OtherUserProfileDto`, `VisibilityUpdateRequest/Response`, `ReportRequest/Response`, `BlockRequest/Response/Unblock`.
6. Reuse or extend the existing `SupabaseStorageService` for signed URL generation.
7. Update the existing `DiscoveryPreferencesService` to expose the profile-domain aliases (`/api/v1/profile/me/preferences`).
8. Add a Flyway migration for the lifestyle column amendments in section 9.
9. Unit and integration tests covering all cases in section 8.
10. Ensure `mvn test` and `mvn compile` pass before declaring the step complete.
