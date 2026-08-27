package com.qaliye.backend.appversion;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppVersionService {

    private final AppVersionProperties properties;

    public AppVersionService(AppVersionProperties properties) {
        this.properties = properties;
    }

    public AppVersionResponse getVersion(String platformParam) {
        AppPlatform platform = parsePlatform(platformParam);
        AppVersionProperties.PlatformConfig config = switch (platform) {
            case ANDROID -> properties.getAndroid();
            case IOS -> properties.getIos();
        };
        return new AppVersionResponse(
                platform.toString(),
                config.getLatest(),
                config.getMinimum(),
                config.isForceUpdate(),
                config.getStoreUrl()
        );
    }

    private AppPlatform parsePlatform(String value) {
        try {
            return AppPlatform.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
