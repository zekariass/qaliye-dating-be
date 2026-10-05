package com.qaliye.backend.videocall.provider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wires the active {@link VideoCallProvider} from {@code video.platform}.
 * Fails fast at startup when the configured platform has no provider bean.
 *
 * <p>The resolved bean is {@link Primary @Primary} — provider impls stay
 * registered for the by-name lookup here, while consumers that inject a
 * single {@code VideoCallProvider} unambiguously get the configured one.
 */
@Configuration
@EnableConfigurationProperties(VideoProperties.class)
public class VideoConfig {

    @Bean
    @Primary
    public VideoCallProvider activeVideoCallProvider(List<VideoCallProvider> providers,
                                                     VideoProperties props) {
        Map<String, VideoCallProvider> byName = providers.stream()
                .collect(Collectors.toMap(VideoCallProvider::name, Function.identity()));
        VideoCallProvider active = byName.get(props.getPlatform());
        if (active == null) {
            throw new IllegalStateException(
                    "video.platform=" + props.getPlatform()
                            + " has no VideoCallProvider bean. Available: "
                            + byName.keySet());
        }
        return active;
    }
}
