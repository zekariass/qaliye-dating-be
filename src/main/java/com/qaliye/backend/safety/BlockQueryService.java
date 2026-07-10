package com.qaliye.backend.safety;

import com.qaliye.backend.safety.cursor.BlockCursorCodec;
import com.qaliye.backend.storage.SupabaseStorageService;
import com.qaliye.backend.user.dto.BlockItemDto;
import com.qaliye.backend.user.dto.BlockedUserAddressDto;
import com.qaliye.backend.user.dto.BlockedUserDto;
import com.qaliye.backend.user.dto.BlocksPageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class BlockQueryService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final String BASE_LIST_BLOCKS_SQL = """
            SELECT
                ub.id AS block_id,
                ub.created_at AS blocked_at,
                ub.reason AS block_reason,
                p.user_id AS blocked_user_id,
                p.display_name,
                a.id AS address_id,
                a.country_code,
                a.country_name,
                a.city,
                pp.id AS photo_id,
                pp.storage_bucket,
                pp.storage_path
            FROM user_blocks ub
            JOIN profiles p ON p.user_id = ub.blocked_user_id
            LEFT JOIN app_users au ON au.id = p.user_id
            LEFT JOIN addresses a ON a.id = au.address_id
            LEFT JOIN LATERAL (
                SELECT id, storage_bucket, storage_path
                FROM profile_photos
                WHERE user_id = ub.blocked_user_id
                  AND is_primary = TRUE
                  AND moderation_status = 'APPROVED'
                  AND deleted_at IS NULL
                LIMIT 1
            ) pp ON TRUE
            WHERE ub.blocker_user_id = :blockerId
              AND ub.status = 'ACTIVE'
            """;

    private static final String CURSOR_CLAUSE = """
              AND (ub.created_at, ub.id) < (:afterAt, :afterId)
            """;

    private static final String ORDER_LIMIT_CLAUSE = """
            ORDER BY ub.created_at DESC, ub.id DESC
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SupabaseStorageService storageService;
    private final BlockCursorCodec cursorCodec;

    public BlockQueryService(NamedParameterJdbcTemplate jdbc,
                             SupabaseStorageService storageService,
                             BlockCursorCodec cursorCodec) {
        this.jdbc = jdbc;
        this.storageService = storageService;
        this.cursorCodec = cursorCodec;
    }

    @Transactional(readOnly = true)
    public BlocksPageResponse listActiveBlocks(UUID blockerId, String cursor, Integer requestedLimit) {
        int limit = normalizeLimit(requestedLimit);
        BlockCursorCodec.CursorState state = decodeCursor(cursor);

        String sql = BASE_LIST_BLOCKS_SQL;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("blockerId", blockerId)
                .addValue("limit", limit + 1);

        if (state != null) {
            sql += CURSOR_CLAUSE;
            params.addValue("afterAt", OffsetDateTime.ofInstant(state.blockedAt(), ZoneOffset.UTC))
                  .addValue("afterId", state.lastId());
        }
        sql += ORDER_LIMIT_CLAUSE;

        List<RawBlockRow> rows = jdbc.query(sql, params, (rs, rowNum) -> new RawBlockRow(
                rs.getObject("block_id", UUID.class),
                rs.getObject("blocked_at", OffsetDateTime.class),
                rs.getString("block_reason"),
                rs.getObject("blocked_user_id", UUID.class),
                rs.getString("display_name"),
                rs.getObject("address_id", UUID.class),
                rs.getString("country_code"),
                rs.getString("country_name"),
                rs.getString("city"),
                rs.getObject("photo_id", UUID.class),
                rs.getString("storage_bucket"),
                rs.getString("storage_path")));

        boolean hasMore = rows.size() > limit;
        List<RawBlockRow> pageRows = hasMore ? rows.subList(0, limit) : rows;

        List<BlockItemDto> items = pageRows.stream()
                .map(this::toBlockItemDto)
                .toList();

        String nextCursor = null;
        if (hasMore && !pageRows.isEmpty()) {
            RawBlockRow last = pageRows.get(pageRows.size() - 1);
            nextCursor = cursorCodec.encode(new BlockCursorCodec.CursorState(
                    last.blockedAt.toInstant(), last.blockId));
        }

        return new BlocksPageResponse(items, nextCursor, hasMore);
    }

    private BlockItemDto toBlockItemDto(RawBlockRow r) {
        BlockedUserAddressDto address = r.addressId != null
                ? new BlockedUserAddressDto(r.addressId, r.countryCode, r.countryName, r.city)
                : null;

        String photoUrl = null;
        if (r.storageBucket != null && r.storagePath != null) {
            photoUrl = storageService.generateSignedUrl(r.storageBucket, r.storagePath, 3600);
        }

        BlockedUserDto blockedUser = new BlockedUserDto(
                r.blockedUserId,
                r.displayName,
                address,
                photoUrl,
                r.photoId);

        return new BlockItemDto(r.blockId, r.blockedAt.toInstant(), r.reason, blockedUser);
    }

    private BlockCursorCodec.CursorState decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        return cursorCodec.decode(cursor);
    }

    private int normalizeLimit(Integer requested) {
        if (requested == null) return DEFAULT_LIMIT;
        return Math.max(1, Math.min(requested, MAX_LIMIT));
    }

    private record RawBlockRow(
            UUID blockId,
            OffsetDateTime blockedAt,
            String reason,
            UUID blockedUserId,
            String displayName,
            UUID addressId,
            String countryCode,
            String countryName,
            String city,
            UUID photoId,
            String storageBucket,
            String storagePath
    ) {}
}
