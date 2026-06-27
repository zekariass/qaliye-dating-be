package com.qaliye.backend.notifications.repository;

import com.qaliye.backend.notifications.entity.UserNotificationPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferencesRepository
        extends JpaRepository<UserNotificationPreferences, UUID> {

    Optional<UserNotificationPreferences> findByUserId(UUID userId);

    @Modifying
    @Query(value = """
            INSERT INTO user_notification_preferences (user_id)
            VALUES (:userId)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("userId") UUID userId);
}
