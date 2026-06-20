package com.qaliye.backend.payments.repository;

import com.qaliye.backend.payments.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, UUID> {

    Optional<SubscriptionPlan> findByPlanCodeAndCountryCode(String planCode, String countryCode);
}
