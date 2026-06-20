package com.qaliye.backend.user.repository;

import com.qaliye.backend.user.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByIdAndDeletedAtIsNull(UUID id);
}
