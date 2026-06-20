package com.qaliye.backend.payments.repository;

import com.qaliye.backend.payments.entity.ActiveBoost;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ActiveBoostRepository extends JpaRepository<ActiveBoost, UUID> {
}
