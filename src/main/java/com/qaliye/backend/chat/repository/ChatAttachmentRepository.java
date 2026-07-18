package com.qaliye.backend.chat.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class ChatAttachmentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ChatAttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AttachmentRow(
            UUID id,
            UUID messageId,
            String attachmentType,
            String fileName,
            String contentType,
            long fileSizeBytes,
            String storageBucket,
            String storagePath,
            Long durationMs,
            OffsetDateTime createdAt
    ) {}

    private static final String INSERT_SQL = """
            INSERT INTO chat_attachments
                (id, message_id, attachment_type, file_name, content_type,
                 file_size_bytes, storage_bucket, storage_path, duration_ms)
            VALUES
                (:id, :messageId, :attachmentType, :fileName, :contentType,
                 :fileSizeBytes, :storageBucket, :storagePath, :durationMs)
            RETURNING id, message_id, attachment_type, file_name, content_type,
                      file_size_bytes, storage_bucket, storage_path, duration_ms, created_at
            """;

    private static final String FIND_BY_MESSAGE_IDS_SQL = """
            SELECT id, message_id, attachment_type, file_name, content_type,
                   file_size_bytes, storage_bucket, storage_path, duration_ms, created_at
            FROM chat_attachments
            WHERE message_id IN (:messageIds)
            ORDER BY created_at ASC, id ASC
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT id, message_id, attachment_type, file_name, content_type,
                   file_size_bytes, storage_bucket, storage_path, duration_ms, created_at
            FROM chat_attachments
            WHERE id = :id
            """;

    public AttachmentRow insert(UUID messageId, String attachmentType, String fileName,
                                String contentType, long fileSizeBytes,
                                String storageBucket, String storagePath, Long durationMs) {
        UUID id = UUID.randomUUID();
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("messageId", messageId)
                .addValue("attachmentType", attachmentType)
                .addValue("fileName", fileName)
                .addValue("contentType", contentType)
                .addValue("fileSizeBytes", fileSizeBytes)
                .addValue("storageBucket", storageBucket)
                .addValue("storagePath", storagePath)
                .addValue("durationMs", durationMs);
        return jdbc.queryForObject(INSERT_SQL, params, this::mapRow);
    }

    public Map<UUID, List<AttachmentRow>> findByMessageIds(Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return Map.of();
        var params = new MapSqlParameterSource()
                .addValue("messageIds", messageIds);
        List<AttachmentRow> rows = jdbc.query(FIND_BY_MESSAGE_IDS_SQL, params, this::mapRow);
        return rows.stream().collect(Collectors.groupingBy(AttachmentRow::messageId));
    }

    public Optional<AttachmentRow> findById(UUID id) {
        List<AttachmentRow> rows = jdbc.query(
                FIND_BY_ID_SQL, new MapSqlParameterSource("id", id), this::mapRow);
        return rows.stream().findFirst();
    }

    private AttachmentRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AttachmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getString("attachment_type"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("file_size_bytes"),
                rs.getString("storage_bucket"),
                rs.getString("storage_path"),
                rs.getObject("duration_ms", Long.class),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }
}
