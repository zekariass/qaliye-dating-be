package com.qaliye.backend.notifications.repository;

import com.qaliye.backend.notifications.entity.NotificationCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationCampaignRepository
        extends JpaRepository<NotificationCampaign, UUID> {

    Optional<NotificationCampaign> findByCampaignKey(String campaignKey);

    Page<NotificationCampaign> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT c FROM NotificationCampaign c WHERE c.status = :status ORDER BY c.createdAt DESC")
    Page<NotificationCampaign> findByStatus(@Param("status") String status, Pageable pageable);

    @Query("SELECT c FROM NotificationCampaign c WHERE c.status IN ('SCHEDULED', 'SENDING') ORDER BY c.scheduledAt ASC NULLS LAST")
    List<NotificationCampaign> findActiveCampaigns();
}
