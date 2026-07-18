# Image Moderation — Threshold Reference & Tuning Guide

## Overview

Qaliye image moderation runs two Rekognition modules sequentially:

1. **Face Detection** (`DetectFaces`) — ensures the photo contains a clear, adequately-sized face.
2. **Nudity Moderation** (`DetectModerationLabels`) — filters explicit and suggestive content.

All thresholds are configurable via environment variables at runtime, no recompilation required.

---

## Face Detection Thresholds

| Env Var | Default | Rekognition Field | Description |
|---|---|---|---|
| `IMAGE_FACE_MIN_CONFIDENCE` | `95.0` | `FaceDetail.Confidence` | Minimum confidence (0–100) that the bounding box contains a human face. |
| `IMAGE_FACE_MIN_BRIGHTNESS` | `25.0` | `FaceDetail.Quality.Brightness` | Minimum face-region brightness. Photos shot in very dim light fail this. |
| `IMAGE_FACE_MIN_SHARPNESS` | `40.0` | `FaceDetail.Quality.Sharpness` | Minimum face-region sharpness. Blurry photos fail this. |
| `IMAGE_FACE_MIN_SIZE_PERCENT` | `20.0` | `BoundingBox.Width × Height × 100` | Minimum face area as a percentage of the full image. Prevents distant/tiny-face photos. |
| `IMAGE_FACE_OCCLUSION_CHECK_ENABLED` | `false` | `FaceDetail.FaceOccluded.Value` | When `true`, rejects faces that Rekognition believes are partially covered. Disabled by default because occlusion detection is unreliable at lower resolution. |

### Tuning Guidance

- **Confidence (95.0):** Reliable for studio-quality photos. Consider lowering to `85.0` if users report rejections of valid photos taken in low-end devices or unusual angles.
- **Brightness (25.0):** Very permissive. The purpose is to catch pitch-black photos, not dim selfies. Raise to `40.0` if poor-lighting photos are causing UX issues.
- **Sharpness (40.0):** Moderately strict. Front cameras on mid-range phones regularly hit 60–80+. Lower to `20.0` if outdoor action photos are being rejected.
- **Face size (20.0%):** Rejects group shots where the user's face occupies less than 20% of the image area. Raise to `25.0` if you want to be stricter (closer selfies only).
- **Occlusion:** Leave disabled until you have empirical data on false-positive rates. Sunglasses and hair across face can trigger false positives.

---

## Nudity Moderation Thresholds

| Env Var | Default | Description |
|---|---|---|
| `IMAGE_NUDITY_MIN_CONFIDENCE` | `90.0` | Minimum label confidence (0–100) to act on. Labels below this threshold are ignored. |
| `IMAGE_SUGGESTIVE_CONTENT_ACTION` | `MANUAL_REVIEW` | What to do when suggestive (non-explicit) labels fire. One of `APPROVE`, `MANUAL_REVIEW`, `REJECT`. |

### Label Classification

| Category | Rekognition Labels | Action |
|---|---|---|
| **Explicit nudity** | `Explicit Nudity`, `Nudity`, `Graphic Male/Female Nudity`, `Sexual Activity`, `Graphic Sexual Activity`, `Illustrated Explicit Nudity` | Always **REJECTED** |
| **Suggestive** | `Suggestive`, `Partial Nudity`, `Revealing Clothes`, `Female/Male Swimwear Or Underwear`, `Barechested Male`, `Sexual Situations` | Determined by `IMAGE_SUGGESTIVE_CONTENT_ACTION` |
| **Everything else** | Violence, Gore, Hate Symbols, etc. | **Ignored** (intentional — moderation is scoped to nudity/sexual content only) |

### Tuning Guidance

- **Confidence (90.0):** The Rekognition team recommends 50–80 for broad filtering and 90+ for precision. At 90 you get very few false positives on non-nude content; the trade-off is occasional missed near-threshold images.
- **Suggestive action (`MANUAL_REVIEW`):** This is the safest production default. `APPROVE` is appropriate when your user base reliably uploads modest content (e.g. conservative app). `REJECT` is appropriate only if you want zero tolerance — expect significantly more false-positive rejections.
- **Swimwear / barechested:** If your app serves regions where swimwear profile photos are expected, you can set `IMAGE_SUGGESTIVE_CONTENT_ACTION=APPROVE` while keeping explicit nudity rejection, because suggestive and explicit follow separate code paths.

---

## Decision Precedence

When both modules are enabled, the combined decision follows this precedence:

```
REJECTED > MANUAL_REVIEW > APPROVED > SKIPPED
```

A REJECTED face result overrides a MANUAL_REVIEW nudity result.

---

## Feature Flags

| Env Var | Effect |
|---|---|
| `IMAGE_MODERATION_ENABLED=false` | All photos are automatically **APPROVED** with status `SKIPPED`. No Rekognition calls made. |
| `IMAGE_FACE_DETECTION_ENABLED=false` | Only nudity moderation runs. Photos with no face still pass. |
| `IMAGE_NUDITY_MODERATION_ENABLED=false` | Only face detection runs. |

---

## Retry Behaviour

| Env Var | Default | Description |
|---|---|---|
| `IMAGE_MODERATION_MAX_RETRIES` | `3` | Maximum retry attempts per photo. After exhaustion the row stays in `ERROR` state. |
| `IMAGE_MODERATION_RETRY_INITIAL_DELAY_MS` | `1000` | Seed value for exponential backoff + jitter. |

The `ModerationRetryWorker` Quartz job runs every 5 minutes and:
1. Retries `ERROR` rows whose `retry_after` timestamp has elapsed.
2. Recovers stalled `PENDING` photos (created > 5 minutes ago) that never received a Rekognition result (e.g. a missed webhook).

---

## IAM Policy

Grant the application user/role only the two required permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "QaliyeRekognitionModeration",
      "Effect": "Allow",
      "Action": [
        "rekognition:DetectFaces",
        "rekognition:DetectModerationLabels"
      ],
      "Resource": "*"
    }
  ]
}
```

See `docs/aws-rekognition-iam-policy.json` for the exact policy document to attach.

> **Note:** `Resource: "*"` is required for Rekognition byte-based (non-S3) API calls. You cannot further scope it to a bucket because images are sent directly as bytes, not via S3 ARNs.
