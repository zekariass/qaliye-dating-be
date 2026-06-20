package com.qaliye.backend.notifications.repository;

import com.qaliye.backend.notifications.entity.NotificationDevice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeviceRepository extends JpaRepository<NotificationDevice, UUID> {

    List<NotificationDevice> findByUserIdInAndIsActiveTrue(List<UUID> userIds);

    Optional<NotificationDevice> findByDeviceToken(String deviceToken);
}
