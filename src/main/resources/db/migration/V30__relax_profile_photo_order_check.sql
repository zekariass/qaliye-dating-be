-- The upper bound of 8 on photo_order prevents the two-pass reorder strategy
-- (shift-to-scratch-range then assign final positions) used to avoid transient
-- unique constraint violations when photos swap positions.  MAX_PHOTOS (6) is
-- enforced at the service layer, so the DB check only needs a lower bound.
ALTER TABLE profile_photos
    DROP CONSTRAINT profile_photos_photo_order_check;

ALTER TABLE profile_photos
    ADD CONSTRAINT profile_photos_photo_order_check
        CHECK (photo_order >= 0);
