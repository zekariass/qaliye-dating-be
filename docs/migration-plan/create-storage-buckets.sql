-- =============================================================================
-- Production Storage Bucket Setup Script
-- =============================================================================
-- Run this against the production Supabase database (via SQL Editor or psql)
-- using the service-role key / postgres connection.
--
-- All buckets are PRIVATE. Some buckets allow direct client uploads via
-- Supabase Storage RLS policies (profile-photos, verification-selfies,
-- payment-receipts). All other buckets are accessed only through Spring Boot
-- via the service-role key (which bypasses RLS).
-- =============================================================================

-- 1. Profile Photos (10 MB, images only)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'profile-photos',
    'profile-photos',
    FALSE,
    10485760,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE
SET public             = EXCLUDED.public,
    file_size_limit    = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

-- 2. Verification Selfies (10 MB, images only)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'verification-selfies',
    'verification-selfies',
    FALSE,
    10485760,
    ARRAY['image/jpeg', 'image/png', 'image/webp']
)
ON CONFLICT (id) DO UPDATE
SET public             = EXCLUDED.public,
    file_size_limit    = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

-- 3. Chat Attachments (25 MB, images + audio)
--    Configurable via CHAT_ATTACHMENTS_BUCKET env var (default: chat-attachments)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'chat-attachments',
    'chat-attachments',
    FALSE,
    26214400,
    ARRAY[
        'image/jpeg',
        'image/png',
        'image/webp',
        'image/gif',
        'image/bmp',
        'image/heic',
        'image/heif',
        'image/avif',
        'image/tiff',
        'audio/m4a',
        'audio/mp4',
        'audio/aac',
        'audio/mpeg',
        'audio/x-m4a',
        'audio/mp3',
        'audio/ogg',
        'audio/wav',
        'audio/x-wav',
        'audio/webm',
        'audio/flac',
        'audio/3gpp',
        'audio/amr'
    ]
)
ON CONFLICT (id) DO UPDATE
SET public             = EXCLUDED.public,
    file_size_limit    = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

-- 4. Support Attachments (50 MB, images + PDF + audio)
--    Already created by V32 migration, but included here for completeness.
--    Configurable via SUPPORT_ATTACHMENTS_BUCKET env var (default: support-attachments)
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'support-attachments',
    'support-attachments',
    FALSE,
    52428800,
    ARRAY[
        'image/jpeg',
        'image/png',
        'image/webp',
        'application/pdf',
        'text/plain',
        'audio/m4a',
        'audio/mp4',
        'audio/aac',
        'audio/mpeg',
        'audio/wav',
        'audio/webm',
        'audio/x-m4a'
    ]
)
ON CONFLICT (id) DO UPDATE
SET public             = EXCLUDED.public,
    file_size_limit    = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

-- 5. Payment Receipts (10 MB, images + PDF)
--    Client uploads receipt directly, then calls POST /manual-transfer/receipt
--    Client passes the bucket name via ManualReceiptRequest.receiptStorageBucket
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'payment-receipts',
    'payment-receipts',
    FALSE,
    10485760,
    ARRAY['image/jpeg', 'image/png', 'image/webp', 'application/pdf']
)
ON CONFLICT (id) DO UPDATE
SET public             = EXCLUDED.public,
    file_size_limit    = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;


-- =============================================================================
-- STORAGE RLS POLICIES
-- =============================================================================
-- Supabase restricts ALTER/CREATE POLICY on storage.objects via SQL Editor.
-- Create these policies through the Supabase Dashboard instead:
--   Dashboard → Storage → Policies
--
-- ── profile-photos (4 policies) ─────────────────────────────────────────────
--
-- 1. Name:      Authenticated users can read profile photos
--    Operation: SELECT
--    Role:       authenticated
--    USING:      bucket_id = 'profile-photos'
--
-- 2. Name:      Users can upload their own profile photos
--    Operation: INSERT
--    Role:       authenticated
--    WITH CHECK: bucket_id = 'profile-photos'
--                AND (storage.foldername(name))[1] = (auth.uid())::text
--
-- 3. Name:      Users can update their own profile photos
--    Operation: UPDATE
--    Role:       authenticated
--    USING:      bucket_id = 'profile-photos'
--                AND (storage.foldername(name))[1] = (auth.uid())::text
--
-- 4. Name:      Users can delete their own profile photos
--    Operation: DELETE
--    Role:       authenticated
--    USING:      bucket_id = 'profile-photos'
--                AND (storage.foldername(name))[1] = (auth.uid())::text
--
-- ── verification-selfies (2 policies) ───────────────────────────────────────
--
-- 5. Name:      Users can upload their own verification selfies
--    Operation: INSERT
--    Role:       authenticated
--    WITH CHECK: bucket_id = 'verification-selfies'
--                AND (storage.foldername(name))[1] = (auth.uid())::text
--
-- 6. Name:      Users can read their own verification selfies
--    Operation: SELECT
--    Role:       authenticated
--    USING:      bucket_id = 'verification-selfies'
--                AND (storage.foldername(name))[1] = (auth.uid())::text
--
-- ── payment-receipts (2 policies) ───────────────────────────────────────────
--
-- 7. Name:      Users can upload their own payment receipts
--    Operation: INSERT
--    Role:       authenticated
--    WITH CHECK: bucket_id = 'payment-receipts'
--                AND (storage.foldername(name))[1] = (auth.uid())::text
--
-- 8. Name:      Users can read their own payment receipts
--    Operation: SELECT
--    Role:       authenticated
--    USING:      bucket_id = 'payment-receipts'
--                AND (storage.foldername(name))[1] = (auth.uid())::text
-- =============================================================================
