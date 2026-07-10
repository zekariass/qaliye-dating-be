# Language, Ethnicity & Discovery Preferences — Frontend Implementation Guide

> **Scope:** Non-admin (regular user) surfaces only.  
> **Base URL:** `/api/v1`  
> **Auth:** All endpoints require a valid Supabase JWT (`Authorization: Bearer <token>`).

---

## Overview of Changes

| Area | What Changed |
|---|---|
| Profile: ethnicity | Was a single free-text `String`. Now a **list of catalog objects** (`EthnicityOption[]`) + optional `ethnicityOtherText`. |
| Profile: languages | Was a `String[]` of raw text values. Now a **list of catalog objects** (`LanguageOption[]`). |
| Profile update | `ethnicity` and `languages` fields **removed** from `PUT /profile/me`. Use the new dedicated endpoint instead. |
| New endpoint | `PUT /profile/me/cultural-details` — sets ethnicity and language by catalog UUID. |
| New catalog endpoints | `GET /catalog/languages` and `GET /catalog/ethnicities` — paginated, filterable lists. |
| Discovery preferences | Expanded with location mode, children prefs, religion prefs, and language/ethnicity preference filters. |
| Discovery cards | Each card now includes `ethnicities: EthnicityOption[]` and `languages: LanguageOption[]` instead of a single `ethnicity` string. |

---

## 1. Catalog Endpoints

Use these to populate pickers before the user selects their own languages/ethnicities or sets discovery preferences.

### `GET /api/v1/catalog/languages`

Returns a paginated list of active language options.

**Query parameters**

| Param | Type | Required | Description |
|---|---|---|---|
| `countryCode` | `string` | No | Filter by 2-letter ISO country code (e.g. `ET`, `ER`, `GB`). Omit for all countries. |
| `q` | `string` | No | Partial name/code search (case-insensitive). |
| `limit` | `integer` | No | Max results, default `100`, max `200`. |
| `offset` | `integer` | No | Pagination offset, default `0`. |

**Response `200 OK`** — `LanguageOption[]`

```json
[
  {
    "id": "uuid",
    "code": "am",
    "countryCode": "ET",
    "name": "Amharic",
    "nativeName": "አማርኛ"
  },
  {
    "id": "uuid",
    "code": "om",
    "countryCode": "ET",
    "name": "Afaan Oromo",
    "nativeName": "Afaan Oromoo"
  }
]
```

---

### `GET /api/v1/catalog/ethnicities`

Returns a paginated list of active ethnicity options.

**Query parameters**

| Param | Type | Required | Description |
|---|---|---|---|
| `countryCode` | `string` | No | Filter by 2-letter ISO country code. Omit for all. |
| `q` | `string` | No | Partial name/code search. |
| `limit` | `integer` | No | Max results, default `100`, max `200`. |
| `offset` | `integer` | No | Pagination offset, default `0`. |

**Response `200 OK`** — `EthnicityOption[]`

```json
[
  {
    "id": "uuid",
    "code": "amhara",
    "countryCode": "ET",
    "name": "Amhara",
    "region": "East Africa"
  },
  {
    "id": "uuid",
    "code": "tigrinya",
    "countryCode": "ER",
    "name": "Eritrean Tigrinya",
    "region": "East Africa"
  }
]
```

> **Tip:** The `id` (UUID) is what you send back to the backend when saving a user's selections or preferences. Store both `id` and `name` locally so you can display the label without re-fetching.

---

## 2. Profile — Cultural Details

### Shared Types

```typescript
interface LanguageOption {
  id: string;          // UUID — use this for all API writes
  code: string;        // e.g. "am", "ti", "om"
  countryCode: string; // e.g. "ET", "ER"
  name: string;        // e.g. "Amharic"
  nativeName: string;  // e.g. "አማርኛ"
}

interface EthnicityOption {
  id: string;          // UUID — use this for all API writes
  code: string;        // e.g. "amhara", "tigrinya"
  countryCode: string; // e.g. "ET", "ER"
  name: string;        // e.g. "Amhara"
  region: string;      // e.g. "East Africa"
}
```

---

### `PUT /api/v1/profile/me/cultural-details`

**New dedicated endpoint** for updating a user's own ethnicity and language selections. This replaces the old `ethnicity` and `languages` fields that were previously part of `PUT /profile/me`.

**Request body**

```json
{
  "ethnicityIds": ["uuid1", "uuid2"],
  "ethnicityOtherText": "Optional free-text if not in catalog",
  "languageIds": ["uuid3", "uuid4"]
}
```

| Field | Type | Required | Validation |
|---|---|---|---|
| `ethnicityIds` | `UUID[]` | No | Max 10 items. Each UUID must be a valid active catalog entry. |
| `ethnicityOtherText` | `string` | No | Max 200 characters. |
| `languageIds` | `UUID[]` | No | Max 20 items. Each UUID must be a valid active catalog entry. |

**Response `200 OK`** — full `ProfileMeDto` (see §3).

**Error cases**

| Status | Error | Meaning |
|---|---|---|
| `422` | `One or more ethnicity IDs are unknown or inactive.` | A provided UUID does not exist or is disabled in the catalog. |
| `422` | `One or more language IDs are unknown or inactive.` | Same for language UUIDs. |

---

### `PUT /api/v1/profile/me` — Updated Fields

The old free-text `ethnicity` (`string`) and raw `languages` (`string[]`) fields have been **removed**. Cultural details are now accepted as **optional, catalog-based fields** directly on this endpoint. When omitted, existing saved values are preserved. All submitted changes (profile fields + cultural details) are saved atomically.

**Accepted fields:**

```json
{
  "displayName": "string",
  "gender": "MALE|FEMALE",
  "dateOfBirth": "YYYY-MM-DD",
  "heightCm": 175,
  "residencyType": "ETHIOPIA|ERITREA|DIASPORA",
  "bio": "string",
  "nationality": "string",
  "religion": "string",
  "educationLevel": "string",
  "occupation": "string",
  "relationshipIntention": "MARRIAGE|SERIOUS_RELATIONSHIP|LONG_TERM|FRIENDSHIP|NOT_SURE_YET",
  "maritalStatus": "string",
  "hasChildren": true,
  "wantsChildren": false,
  "smoking": true,
  "drinking": false,
  "smokingDetail": "NO|YES|OCCASIONALLY|TRYING_TO_QUIT",
  "drinkingDetail": "NO|SOCIALLY|OCCASIONALLY|YES",
  "activityLevel": "SEDENTARY|LIGHT|MODERATE|ACTIVE|VERY_ACTIVE",
  "interests": ["string"],
  "discoveryMode": "PUBLIC|INCOGNITO",
  "ethnicityIds": ["uuid1", "uuid2"],
  "ethnicityOtherText": "Optional free-text description",
  "languageIds": ["uuid3", "uuid4"]
}
```

**Cultural detail field rules:**

| Field | Type | Required | Behaviour when omitted |
|---|---|---|---|
| `ethnicityIds` | `UUID[]` | No — max 10 | Existing `ethnicity_ids` preserved |
| `ethnicityOtherText` | `string` | No — max 200 chars | Existing `ethnicity_other_text` preserved; send `""` to clear |
| `languageIds` | `UUID[]` | No — max 20 | Existing `language_ids` preserved |

> **Null vs omit:** A missing field (not present in JSON) preserves the existing value. Sending `"ethnicityIds": []` explicitly clears the selection.

**Error cases for cultural fields:**

| Status | Error | Meaning |
|---|---|---|
| `422` | `One or more ethnicity IDs are unknown or inactive.` | A provided UUID does not exist or is disabled in the catalog. |
| `422` | `One or more language IDs are unknown or inactive.` | Same for language UUIDs. |

The dedicated `PUT /profile/me/cultural-details` endpoint remains available for cases where only cultural details need to be updated without touching other profile fields.

---

## 3. Profile Response Shape Changes

### `GET /api/v1/profile/me` → `ProfileMeDto`

The `ethnicities` and `languages` fields now return expanded catalog objects instead of raw strings.

**Changed fields:**

```jsonc
{
  // Before: "ethnicity": "AMHARA"
  // Now:
  "ethnicities": [
    { "id": "uuid", "code": "amhara", "countryCode": "ET", "name": "Amhara", "region": "East Africa" }
  ],
  "ethnicityOtherText": null,

  // Before: "languages": ["Amharic", "English"]
  // Now:
  "languages": [
    { "id": "uuid", "code": "am", "countryCode": "ET", "name": "Amharic", "nativeName": "አማርኛ" },
    { "id": "uuid", "code": "en", "countryCode": "ET", "name": "English", "nativeName": "English" }
  ]
}
```

Both fields are always arrays (never `null`). An empty array `[]` means not set.

The full response also includes `discoveryPreferences` — see §4.

---

### `GET /api/v1/profile/{userId}` → `OtherUserProfileDto`

Same change applies when viewing another user's profile:

```jsonc
{
  "ethnicities": [ /* EthnicityOption... */ ],
  "ethnicityOtherText": "string | null",
  "languages": [ /* LanguageOption... */ ]
}
```

---

## 4. Discovery Preferences

### `GET /api/v1/profile/me/preferences` or `GET /api/v1/discovery/preferences`

Both return the same `DiscoveryPreferencesDto`. The `/discovery/preferences` variant wraps it under a `"preferences"` key.

**Full response shape:**

```jsonc
{
  "interestedInGender": "MALE|FEMALE",
  "minAge": 18,
  "maxAge": 99,
  "maxDistanceKm": 50,
  "openToLongDistance": false,
  "openToRelocation": false,
  "showVerifiedOnly": false,

  "locationMode": "anywhere",
  "specificCountryCodes": [],
  "expandSearchWhenLimited": false,
  "hasChildrenPreference": "any",
  "wantsChildrenPreference": "any",
  "religionPreferences": [],
  "languagePreferences": [ /* LanguageOption... */ ],
  "ethnicityPreferences": [ /* EthnicityOption... */ ],
  "preferencesVersion": 1
}
```

> **Note:** `maxAge` and `maxDistanceKm` are now **nullable**. A `null` value means no upper limit. Handle this in range sliders — treat `null` as "no cap".

**New field reference:**

| Field | Type | Allowed values / Notes |
|---|---|---|
| `locationMode` | `string` | `"anywhere"` · `"nearby"` · `"diaspora"` · `"specific_countries"` |
| `specificCountryCodes` | `string[]` | 2-letter ISO country codes; only meaningful when `locationMode = "specific_countries"` |
| `expandSearchWhenLimited` | `boolean` | Auto-widen search when results are scarce |
| `hasChildrenPreference` | `string` | `"any"` · `"yes"` · `"no"` |
| `wantsChildrenPreference` | `string` | `"any"` · `"yes"` · `"no"` · `"not_sure"` · `"open_to_discussion"` |
| `religionPreferences` | `string[]` | Free-text religion strings, max 10 |
| `languagePreferences` | `LanguageOption[]` | Expanded catalog objects for the user's language match filters |
| `ethnicityPreferences` | `EthnicityOption[]` | Expanded catalog objects for the user's ethnicity match filters |
| `preferencesVersion` | `integer` | Server-managed version counter — use for client-side cache invalidation |

---

### `PUT /api/v1/profile/me/preferences` or `PUT /api/v1/discovery/preferences`

**Request body:**

```jsonc
{
  "interestedInGender": "FEMALE",
  "minAge": 22,
  "maxAge": 40,
  "maxDistanceKm": 100,
  "openToLongDistance": true,
  "openToRelocation": false,
  "showVerifiedOnly": false,

  "locationMode": "anywhere",
  "specificCountryCodes": ["GB", "US"],
  "expandSearchWhenLimited": false,
  "hasChildrenPreference": "any",
  "wantsChildrenPreference": "any",
  "religionPreferences": ["Islam", "Christian"],
  "languagePreferenceIds": ["uuid1", "uuid2"],
  "ethnicityPreferenceIds": ["uuid3"]
}
```

| Field | Type | Required | Validation |
|---|---|---|---|
| `interestedInGender` | `string` | **Yes** | `MALE` or `FEMALE` |
| `minAge` | `integer` | **Yes** | 18–100 |
| `maxAge` | `integer` | No | 18–120, nullable |
| `maxDistanceKm` | `integer` | No | 1–500, nullable |
| `openToLongDistance` | `boolean` | No | |
| `openToRelocation` | `boolean` | No | |
| `showVerifiedOnly` | `boolean` | No | |
| `locationMode` | `string` | No | `nearby` · `diaspora` · `specific_countries` · `anywhere` |
| `specificCountryCodes` | `string[]` | No | Max 20 |
| `expandSearchWhenLimited` | `boolean` | No | |
| `hasChildrenPreference` | `string` | No | `any` · `yes` · `no` |
| `wantsChildrenPreference` | `string` | No | `any` · `yes` · `no` · `not_sure` · `open_to_discussion` |
| `religionPreferences` | `string[]` | No | Max 10 |
| `languagePreferenceIds` | `UUID[]` | No | Max 20; each must be a valid active catalog UUID |
| `ethnicityPreferenceIds` | `UUID[]` | No | Max 10; each must be a valid active catalog UUID |

> **Important:** `languagePreferenceIds` and `ethnicityPreferenceIds` take **UUIDs** (not the expanded objects). Get these from `GET /catalog/languages` or from the current preferences (`languagePreferences[].id`).

**Response `200 OK`** — `DiscoveryPreferencesDto` (same shape as GET above).

**`PUT /api/v1/discovery/preferences` response wraps with onboarding:**

```jsonc
{
  "preferences": { /* DiscoveryPreferencesDto */ },
  "onboarding": {
    "next_step": "string | null",
    "can_complete_onboarding": true
  }
}
```

---

### `DELETE /api/v1/discovery/preferences`

Resets all preferences. Response shape is the same as PUT but with `"preferences": null`.

---

## 5. Discovery Cards

Each card in the discovery feed now includes expanded catalog objects.

**Changed fields on each card:**

```jsonc
{
  "userId": "uuid",
  "displayName": "string",
  "age": 26,
  "gender": "FEMALE",
  "bio": "string",
  "residencyType": "ETHIOPIA",
  "city": "Addis Ababa",
  "region": "string | null",
  "countryName": "Ethiopia",
  "distanceKm": 5,
  "isVerified": true,
  "relationshipIntention": "MARRIAGE",
  "heightCm": 165,

  "ethnicities": [
    { "id": "uuid", "code": "amhara", "countryCode": "ET", "name": "Amhara", "region": "East Africa" }
  ],

  "nationality": "string | null",
  "religion": "string | null",
  "educationLevel": "string | null",
  "occupation": "string | null",
  "maritalStatus": "string | null",
  "hasChildren": false,
  "wantsChildren": true,
  "smoking": "NO",
  "drinking": "SOCIALLY",

  "languages": [
    { "id": "uuid", "code": "am", "countryCode": "ET", "name": "Amharic", "nativeName": "አማርኛ" }
  ],

  "photos": [ /* DiscoveryPhotoDto[] */ ],
  "promptAnswers": [ /* DiscoveryPromptAnswerDto[] */ ],
  "isBoosted": false,
  "discoveryScore": 0.87,
  "activityStatus": { /* ActivityStatus */ }
}
```

Both `ethnicities` and `languages` are always arrays. An empty `[]` means the user has not set these fields.

---

## 6. Recommended Frontend Flow

### Profile Setup / Onboarding

1. **Fetch catalogs** (once per session or screen open):
   - `GET /catalog/languages?countryCode=ET` — show Habesha-first list; allow searching all countries by omitting `countryCode`.
   - `GET /catalog/ethnicities?countryCode=ET` — same pattern.
2. Present multi-select pickers using `name` for display and `id` for selection state.
3. On save call `PUT /profile/me/cultural-details` with `languageIds` and `ethnicityIds` UUID arrays.

### Preferences Screen

1. `GET /profile/me/preferences` — load to pre-populate the form.
2. Pre-select language/ethnicity chips whose UUIDs appear in `languagePreferences[].id` / `ethnicityPreferences[].id`.
3. On save call `PUT /profile/me/preferences` sending `languagePreferenceIds` and `ethnicityPreferenceIds` as UUID arrays.
4. Show/hide the `specificCountryCodes` picker based on `locationMode === "specific_countries"`.

### Discovery Feed

- Render `ethnicities` as `name` chips or a comma-separated string.
- Render `languages` similarly.
- Hide the section when the array is empty rather than showing a blank field.

---

## 7. Migration Notes for Existing Clients

| Old field / behaviour | New equivalent | Action |
|---|---|---|
| `profile.ethnicity` (string) | `profile.ethnicities` (EthnicityOption[]) | Update all read and display logic |
| `profile.languages` (string[]) | `profile.languages` (LanguageOption[]) | Values are now objects — access `.name` for display |
| `PUT /profile/me` with `ethnicity` | `PUT /profile/me` with `ethnicityIds` (or the dedicated `/cultural-details` endpoint) | Replace free-text string with catalog UUIDs |
| `PUT /profile/me` with `languages` | `PUT /profile/me` with `languageIds` (or the dedicated `/cultural-details` endpoint) | Replace raw string array with catalog UUIDs |
| `discoveryCard.ethnicity` (string) | `discoveryCard.ethnicities` (array) | Update card render logic |
| `preferences.preferredResidencyTypes` | `preferences.locationMode` + `specificCountryCodes` | Map: `["DIASPORA"]` only → `locationMode: "diaspora"`, anything else → `locationMode: "anywhere"` |
| `preferences.maxAge` always present | Now nullable | Guard range sliders — treat `null` as "no upper limit" |
| `preferences.maxDistanceKm` always present | Now nullable | Same guard |
