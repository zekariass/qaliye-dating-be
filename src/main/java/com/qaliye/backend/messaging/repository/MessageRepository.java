package com.qaliye.backend.messaging.repository;

import com.qaliye.backend.messaging.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    Optional<Message> findBySenderUserIdAndClientMessageId(UUID senderId, UUID clientMessageId);
}
