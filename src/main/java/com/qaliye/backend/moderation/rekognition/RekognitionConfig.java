package com.qaliye.backend.moderation.rekognition;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;

/**
 * Registers the AWS Rekognition client and binds {@link ImageModerationProperties}.
 * <p>
 * Credentials are resolved from Spring properties (sourced from env vars via
 * {@code application.yml}):
 * <ul>
 *   <li>If {@code aws.credentials.access-key-id} and {@code aws.credentials.secret-access-key}
 *       are non-blank, {@link StaticCredentialsProvider} is used (supports optional session token).</li>
 *   <li>Otherwise, {@link DefaultCredentialsProvider} is used, which falls back to an
 *       IAM instance profile or {@code ~/.aws/credentials} — ideal for production deployments.</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableConfigurationProperties(ImageModerationProperties.class)
public class RekognitionConfig {

    @Value("${aws.region:eu-west-2}")
    private String region;

    @Value("${aws.credentials.access-key-id:}")
    private String accessKeyId;

    @Value("${aws.credentials.secret-access-key:}")
    private String secretAccessKey;

    @Value("${aws.credentials.session-token:}")
    private String sessionToken;

    @Bean
    public RekognitionClient rekognitionClient() {
        return RekognitionClient.builder()
                .region(Region.of(region))
                .credentialsProvider(resolveCredentialsProvider())
                .build();
    }

    private AwsCredentialsProvider resolveCredentialsProvider() {
        if (StringUtils.hasText(accessKeyId) && StringUtils.hasText(secretAccessKey)) {
            if (StringUtils.hasText(sessionToken)) {
                return StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken));
            }
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }
        return DefaultCredentialsProvider.create();
    }
}
