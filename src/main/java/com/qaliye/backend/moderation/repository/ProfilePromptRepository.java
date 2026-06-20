package com.qaliye.backend.moderation.repository;

import com.qaliye.backend.moderation.entity.ProfilePrompt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProfilePromptRepository extends JpaRepository<ProfilePrompt, UUID> {

    List<ProfilePrompt> findByIsActiveTrue();
}
