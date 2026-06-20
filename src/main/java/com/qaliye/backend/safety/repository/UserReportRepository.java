package com.qaliye.backend.safety.repository;

import com.qaliye.backend.safety.entity.UserReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserReportRepository extends JpaRepository<UserReport, UUID> {
}
