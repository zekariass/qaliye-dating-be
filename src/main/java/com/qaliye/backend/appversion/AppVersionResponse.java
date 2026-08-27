package com.qaliye.backend.appversion;

public record AppVersionResponse(
        String platform,
        String latestVersion,
        String minimumVersion,
        boolean forceUpdate,
        String storeUrl
) {}
