package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.config.DiscoveryProperties;
import com.qaliye.backend.discovery.cursor.DiscoveryCursorCodec;
import com.qaliye.backend.discovery.dto.DiscoveryProfileDto;
import com.qaliye.backend.discovery.dto.DiscoveryProfilesResponse;
import com.qaliye.backend.discovery.exception.ActorIneligibleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DiscoveryFeedService {

    private static final Set<String> VALID_LOCATION_FILTERS =
            Set.of("NEARBY", "ETHIOPIA", "ERITREA", "DIASPORA");

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
    public DiscoveryProfilesResponse fetchProfiles(UUID actorId,
                                                    String locationFilter,
                                                    String cursorToken) {
        if (!VALID_LOCATION_FILTERS.contains(locationFilter)) {
            locationFilter = "NEARBY";
        }

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

        DiscoveryCursorCodec.CursorState cursor = cursorCodec.decode(cursorToken, locationFilter);

        int batchSize = props.getQueue().batchSize();
        int totalEligible = queryService.countEligible(actorId, ctx, locationFilter);

        List<DiscoveryProfileDto> profiles = queryService.fetchProfiles(
                actorId, ctx, locationFilter, batchSize, cursor.offset());

        int newOffset = cursor.offset() + profiles.size();
        boolean hasMore = newOffset < totalEligible;
        String nextCursor = hasMore ? cursorCodec.encode(newOffset, locationFilter) : null;

        return new DiscoveryProfilesResponse(
                profiles,
                nextCursor,
                hasMore,
                totalEligible,
                locationFilter,
                profiles.size(),
                cursor.reset()
        );
    }
}
