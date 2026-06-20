package com.qaliye.backend.messaging.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "matches")
@Getter
@Setter
@NoArgsConstructor
public class Match {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "user_one_id", nullable = false)
    private UUID userOneId;

    @Column(name = "user_two_id", nullable = false)
    private UUID userTwoId;

    @Column(nullable = false)
    private String status;

    @Column(name = "end_reason")
    private String endReason;

    @Column(name = "ended_by_user_id")
    private UUID endedByUserId;

    @Column(name = "ended_at")
    private OffsetDateTime endedAt;

    @Column(name = "user_one_like_action_id")
    private UUID userOneLikeActionId;

    @Column(name = "user_two_like_action_id")
    private UUID userTwoLikeActionId;

    @Column(name = "created_by_action_id")
    private UUID createdByActionId;

    @Column(name = "rewind_eligible_until")
    private OffsetDateTime rewindEligibleUntil;

    @Column(name = "first_message_at")
    private OffsetDateTime firstMessageAt;

    @Column(name = "last_message_at")
    private OffsetDateTime lastMessageAt;

    @Column(name = "user_one_last_read_at")
    private OffsetDateTime userOneLastReadAt;

    @Column(name = "user_two_last_read_at")
    private OffsetDateTime userTwoLastReadAt;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
