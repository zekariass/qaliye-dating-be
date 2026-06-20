package com.qaliye.backend.verification.repository;

import com.qaliye.backend.verification.entity.UserVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserVerificationRepository extends JpaRepository<UserVerification, UUID> {

    Optional<UserVerification> findFirstByUserIdAndStatus(UUID userId, String status);

    List<UserVerification> findByStatusOrderBySubmittedAtAsc(String status, Pageable pageable);
}
