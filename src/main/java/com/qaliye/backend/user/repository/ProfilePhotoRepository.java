package com.qaliye.backend.user.repository;

import com.qaliye.backend.user.entity.ProfilePhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProfilePhotoRepository extends JpaRepository<ProfilePhoto, UUID> {

    List<ProfilePhoto> findByUserIdAndModerationStatus(UUID userId, String moderationStatus);

    int countByUserIdAndModerationStatus(UUID userId, String moderationStatus);

    int countByUserIdAndModerationStatusNot(UUID userId, String moderationStatus);

    java.util.Optional<ProfilePhoto> findFirstByUserIdAndIsPrimaryTrue(UUID userId);

    List<ProfilePhoto> findByUserId(UUID userId);
}
