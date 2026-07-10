package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.config.DiscoveryProperties;
import com.qaliye.backend.discovery.cursor.DiscoveryCursorCodec;
import com.qaliye.backend.discovery.dto.DiscoveryProfileDto;
import com.qaliye.backend.discovery.dto.DiscoveryProfilesResponse;
import com.qaliye.backend.discovery.exception.ActorIneligibleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DiscoveryFeedService {

    private final DiscoveryQueryService queryService;
    private final DiscoveryCursorCodec cursorCodec;
    private final DiscoveryProperties props;

    public DiscoveryFeedService(DiscoveryQueryService queryService,
                                DiscoveryCursorCodec cursorCodec,
                                DiscoveryProperties props) {
        this.queryService = queryService;
        this.cursorCodec = cursorCodec;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public DiscoveryProfilesResponse fetchProfiles(UUID actorId, String cursorToken) {
        DiscoveryQueryService.ActorEligibilityResult eligibility =
                queryService.checkActorEligibilityReason(actorId);
        if (eligibility == DiscoveryQueryService.ActorEligibilityResult.ACCOUNT_INELIGIBLE) {
            throw ActorIneligibleException.accountIneligible();
        }
        if (eligibility == DiscoveryQueryService.ActorEligibilityResult.PROFILE_INCOMPLETE) {
            throw ActorIneligibleException.profileIncomplete();
        }

        DiscoveryQueryService.ActorContext ctx = queryService.loadActorContext(actorId);
        if (ctx == null) {
            throw ActorIneligibleException.profileIncomplete();
        }

        DiscoveryCursorCodec.CursorState cursor = cursorCodec.decode(cursorToken);

        int batchSize = props.getQueue().batchSize();

        boolean skipDistance = false;
        boolean isFirstPage = cursor.lastScore() == null && cursor.lastUserId() == null;
        if (isFirstPage && "nearby".equals(ctx.locationMode()) && ctx.expandSearchWhenLimited()) {
            int nearbyCount = queryService.countEligible(actorId, ctx, false);
            if (nearbyCount < batchSize) {
                skipDistance = true;
            }
        }

        int totalEligible = queryService.countEligible(actorId, ctx, skipDistance);

        DiscoveryQueryService.FetchCursor fetchCursor = cursor.lastScore() != null && cursor.lastUserId() != null
                ? new DiscoveryQueryService.FetchCursor(cursor.lastScore(), cursor.lastUserId())
                : new DiscoveryQueryService.FetchCursor(null, null);

        List<DiscoveryProfileDto> profiles = queryService.fetchProfiles(
                actorId, ctx, batchSize, fetchCursor, skipDistance);

        String nextCursor = null;
        if (!profiles.isEmpty()) {
            DiscoveryProfileDto last = profiles.get(profiles.size() - 1);
            boolean hasMoreLocal = profiles.size() == batchSize;
            nextCursor = hasMoreLocal ? cursorCodec.encode(last.discoveryScore(), last.userId()) : null;
        }
        boolean hasMore = nextCursor != null;

        return new DiscoveryProfilesResponse(
                profiles,
                nextCursor,
                hasMore,
                totalEligible,
                profiles.size(),
                cursor.reset()
        );
    }
}
