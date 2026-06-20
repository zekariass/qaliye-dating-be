package com.qaliye.backend.user.repository;

import com.qaliye.backend.discovery.entity.DiscoveryPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DiscoveryPreferencesRepository extends JpaRepository<DiscoveryPreferences, UUID> {
}
