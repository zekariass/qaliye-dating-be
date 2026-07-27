CREATE UNIQUE INDEX uq_active_redemption_per_user_campaign
    ON promotion_redemptions(campaign_id, user_id)
    WHERE status IN ('RESERVED', 'PROVIDER_PENDING', 'FULFILLED');
