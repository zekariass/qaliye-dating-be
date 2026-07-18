package com.qaliye.backend.support.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SupportMessageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SupportMessageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MessageRow(
            UUID id,
            UUID conversationId,
            long sequenceNumber,
            String senderType,
            String senderDisplayName,
            String body,
            OffsetDateTime createdAt
    ) {}

    public record AttachmentRow(
            UUID id,
            UUID messageId,
            String storageBucket,
            String storagePath,
            String fileName,
            String contentType,
            long fileSizeBytes,
            String attachmentKind,
            Long durationMs,
            OffsetDateTime createdAt
    ) {}

    private MessageRow mapMessage(ResultSet rs, int rowNum) throws SQLException {
        return new MessageRow(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getLong("sequence_number"),
                rs.getString("sender_type"),
                rs.getString("sender_display_name"),
                rs.getString("body"),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private AttachmentRow mapAttachment(ResultSet rs, int rowNum) throws SQLException {
        return new AttachmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getString("storage_bucket"),
                rs.getString("storage_path"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("file_size_bytes"),
                rs.getString("attachment_kind"),
                rs.getObject("duration_ms") != null ? rs.getLong("duration_ms") : null,
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    public List<MessageRow> findMessages(UUID conversationId, Long beforeSequence, int limit) {
        int capped = Math.min(limit, 100);
        long before = beforeSequence != null ? beforeSequence : Long.MAX_VALUE;
        return jdbc.query(
                """
                SELECT m.id, m.conversation_id, m.sequence_number, m.sender_type,
                       p.display_name AS sender_display_name,
                       m.body, m.created_at
                FROM public.support_messages m
                LEFT JOIN profiles p ON p.user_id = m.sender_user_id
                WHERE m.conversation_id = :convId
                  AND m.sequence_number < :before
                ORDER BY m.sequence_number DESC
                LIMIT :limit
                """,
                Map.of("convId", conversationId, "before", before, "limit", capped),
                this::mapMessage
        );
    }

    public List<AttachmentRow> findAttachmentsByMessageIds(List<UUID> messageIds) {
        if (messageIds.isEmpty()) return List.of();
        return jdbc.query(
                """
                SELECT id, message_id, storage_bucket, storage_path,
                       file_name, content_type, file_size_bytes,
                       attachment_kind, duration_ms, created_at
                FROM public.support_attachments
                WHERE message_id = ANY(:ids)
                ORDER BY created_at ASC
                """,
                new MapSqlParameterSource("ids",
                        messageIds.toArray(UUID[]::new)),
                this::mapAttachment
        );
    }

    public Optional<AttachmentRow> findAttachmentWithConversationUserId(UUID attachmentId) {
        List<AttachmentRow> rows = jdbc.query(
                """
                SELECT sa.id, sa.message_id, sa.storage_bucket, sa.storage_path,
                       sa.file_name, sa.content_type, sa.file_size_bytes,
                       sa.attachment_kind, sa.duration_ms, sa.created_at
                FROM public.support_attachments sa
                JOIN public.support_messages sm ON sm.id = sa.message_id
                JOIN public.support_conversations sc ON sc.id = sm.conversation_id
                WHERE sa.id = :id
                """,
                Map.of("id", attachmentId),
                this::mapAttachment
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public UUID findConversationUserIdForAttachment(UUID attachmentId) {
        List<UUID> rows = jdbc.query(
                """
                SELECT sc.user_id
                FROM public.support_attachments sa
                JOIN public.support_messages sm ON sm.id = sa.message_id
                JOIN public.support_conversations sc ON sc.id = sm.conversation_id
                WHERE sa.id = :id
                """,
                Map.of("id", attachmentId),
                (rs, rowNum) -> rs.getObject("user_id", UUID.class)
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public MessageRow callAppendUserMessage(
            UUID userId,
            UUID clientMessageId,
            String body,
            String attachmentsJson,
            String metadataJson) {

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("clientMsgId", clientMessageId)
                .addValue("body", body)
                .addValue("attachments", SupportConversationRepository.toJsonb(attachmentsJson))
                .addValue("metadata", SupportConversationRepository.toJsonb(metadataJson));

        return jdbc.queryForObject(
                """
                SELECT m.id, m.conversation_id, m.sequence_number, m.sender_type,
                       p.display_name AS sender_display_name,
                       m.body, m.created_at
                FROM public.append_support_user_message(
                    :userId, :clientMsgId, :body, :attachments, :metadata
                ) AS m
                LEFT JOIN profiles p ON p.user_id = m.sender_user_id
                """,
                params,
                this::mapMessage
        );
    }

    public MessageRow callAppendStaffMessage(
            UUID conversationId,
            UUID staffUserId,
            UUID clientMessageId,
            String body,
            String attachmentsJson,
            String metadataJson) {

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("convId", conversationId)
                .addValue("staffId", staffUserId)
                .addValue("clientMsgId", clientMessageId)
                .addValue("body", body)
                .addValue("attachments", SupportConversationRepository.toJsonb(attachmentsJson))
                .addValue("metadata", SupportConversationRepository.toJsonb(metadataJson));

        return jdbc.queryForObject(
                """
                SELECT m.id, m.conversation_id, m.sequence_number, m.sender_type,
                       p.display_name AS sender_display_name,
                       m.body, m.created_at
                FROM public.append_support_staff_message(
                    :convId, :staffId, :clientMsgId, :body, :attachments, :metadata
                ) AS m
                LEFT JOIN profiles p ON p.user_id = m.sender_user_id
                """,
                params,
                this::mapMessage
        );
    }

    public List<AttachmentRow> findAttachmentsByMessageId(UUID messageId) {
        return jdbc.query(
                """
                SELECT id, message_id, storage_bucket, storage_path,
                       file_name, content_type, file_size_bytes,
                       attachment_kind, duration_ms, created_at
                FROM public.support_attachments
                WHERE message_id = :msgId
                ORDER BY created_at ASC
                """,
                Map.of("msgId", messageId),
                this::mapAttachment
        );
    }
}
