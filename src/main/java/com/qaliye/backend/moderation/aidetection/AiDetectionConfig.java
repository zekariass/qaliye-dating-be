package com.qaliye.backend.moderation.aidetection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Spring configuration that wires the correct {@link AiDetectionProvider}
 * based on {@code image.ai-detection.provider}.
 * <p>
 * Adding a new provider: implement {@link AiDetectionProvider} and add a
 * {@code case} branch in the switch below — no other changes required.
 * </p>
 * <p>
 * The provider's {@link RestClient} is built with an explicit Jackson
 * converter (the application's {@link ObjectMapper}) placed first in the
 * converter list, so response deserialization does not depend on whatever
 * converter the shared {@code RestClient.Builder} defaults to.
 * </p>
 */
@Configuration
@EnableConfigurationProperties(AiDetectionProperties.class)
public class AiDetectionConfig {

    @Bean
    public AiDetectionProvider aiDetectionProvider(AiDetectionProperties props,
                                                   ObjectMapper objectMapper) {
        String name = props.getProvider();
        if (name == null || name.isBlank()) {
            throw new IllegalStateException(
                    "image.ai-detection.provider must be configured. Valid values: AI_OR_NOT");
        }
        RestClient restClient = RestClient.builder()
                .messageConverters(converters -> converters.add(0,
                        new MappingJackson2HttpMessageConverter(objectMapper)))
                .build();
        return switch (name.toUpperCase()) {
            case "AI_OR_NOT" -> new AiOrNotProvider(props, restClient);
            default -> throw new IllegalStateException(
                    "Unknown image.ai-detection.provider: '" + name + "'. Valid values: AI_OR_NOT");
        };
    }
}
