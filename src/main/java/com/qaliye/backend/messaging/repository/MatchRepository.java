package com.qaliye.backend.messaging.repository;

import com.qaliye.backend.messaging.entity.Match;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MatchRepository extends JpaRepository<Match, UUID> {
}
