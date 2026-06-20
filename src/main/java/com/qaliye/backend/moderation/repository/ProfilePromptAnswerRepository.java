package com.qaliye.backend.moderation.repository;

import com.qaliye.backend.moderation.entity.ProfilePromptAnswer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProfilePromptAnswerRepository extends JpaRepository<ProfilePromptAnswer, UUID> {
}
