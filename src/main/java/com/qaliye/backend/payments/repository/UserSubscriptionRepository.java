package com.qaliye.backend.payments.repository;

import com.qaliye.backend.payments.entity.UserSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserSubscriptionRepository extends JpaRepository<UserSubscription, UUID> {
}
