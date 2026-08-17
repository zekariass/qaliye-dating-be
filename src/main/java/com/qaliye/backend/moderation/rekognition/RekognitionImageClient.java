package com.qaliye.backend.moderation.rekognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.Attribute;
import software.amazon.awssdk.services.rekognition.model.CompareFacesMatch;
import software.amazon.awssdk.services.rekognition.model.CompareFacesRequest;
import software.amazon.awssdk.services.rekognition.model.CompareFacesResponse;
import software.amazon.awssdk.services.rekognition.model.DetectFacesRequest;
import software.amazon.awssdk.services.rekognition.model.DetectFacesResponse;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.ModerationLabel;
import software.amazon.awssdk.services.rekognition.model.RekognitionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Low-level wrapper around the AWS Rekognition SDK.
 * <p>
 * Validates image size before sending, translates SDK exceptions into
 * {@link RekognitionProviderException}, and never logs image bytes or
 * AWS credentials.
 * </p>
 *
 * <p>Rekognition limits for byte-based calls: 5 MB, max 4096×4096 pixels.</p>
 */
@Service
public class RekognitionImageClient {

    private static final Logger log = LoggerFactory.getLogger(RekognitionImageClient.class);

    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final RekognitionClient client;

    public RekognitionImageClient(RekognitionClient client) {
        this.client = client;
    }

    /**
     * Calls DetectFaces with DEFAULT attributes plus FACE_OCCLUDED when
     * {@code includeOcclusion} is true.
     *
     * @throws RekognitionProviderException on AWS errors, throttling, or oversized images
     */
    public List<FaceDetail> detectFaces(byte[] imageBytes, boolean includeOcclusion) {
        validateSize(imageBytes);

        List<Attribute> attrs = new ArrayList<>(List.of(Attribute.DEFAULT));
        if (includeOcclusion) {
            attrs.add(Attribute.FACE_OCCLUDED);
        }

        try {
            DetectFacesRequest request = DetectFacesRequest.builder()
                    .image(toImage(imageBytes))
                    .attributes(attrs)
                    .build();
            DetectFacesResponse response = client.detectFaces(request);
            return response.faceDetails();
        } catch (RekognitionException e) {
            throw toProviderException(e);
        } catch (SdkClientException e) {
            throw toProviderException(e);
        }
    }

    /**
     * Calls DetectModerationLabels, filtering to labels above
     * {@code minConfidence}.
     *
     * @throws RekognitionProviderException on AWS errors, throttling, or oversized images
     */
    public List<ModerationLabel> detectModerationLabels(byte[] imageBytes, float minConfidence) {
        validateSize(imageBytes);

        try {
            DetectModerationLabelsRequest request = DetectModerationLabelsRequest.builder()
                    .image(toImage(imageBytes))
                    .minConfidence(minConfidence)
                    .build();
            DetectModerationLabelsResponse response = client.detectModerationLabels(request);
            return response.moderationLabels();
        } catch (RekognitionException e) {
            throw toProviderException(e);
        } catch (SdkClientException e) {
            throw toProviderException(e);
        }
    }

    public record FaceComparisonResult(float similarity, boolean matched) {}

    /**
     * Compares a source face (selfie) against a target face (profile photo).
     * Returns the highest similarity score from matching face pairs.
     * Returns {@code matched = false} if no faces were found or similarity
     * is below the specified threshold.
     *
     * @throws RekognitionProviderException on AWS errors or oversized images
     */
    public FaceComparisonResult compareFaces(byte[] sourceBytes, byte[] targetBytes,
                                             float similarityThreshold) {
        validateSize(sourceBytes);
        validateSize(targetBytes);

        try {
            CompareFacesRequest request = CompareFacesRequest.builder()
                    .sourceImage(toImage(sourceBytes))
                    .targetImage(toImage(targetBytes))
                    .similarityThreshold(similarityThreshold)
                    .build();
            CompareFacesResponse response = client.compareFaces(request);

            if (response.faceMatches().isEmpty()) {
                return new FaceComparisonResult(0f, false);
            }

            float maxSimilarity = response.faceMatches().stream()
                    .map(CompareFacesMatch::similarity)
                    .max(Float::compareTo)
                    .orElse(0f);

            return new FaceComparisonResult(maxSimilarity, true);
        } catch (RekognitionException e) {
            throw toProviderException(e);
        } catch (SdkClientException e) {
            throw toProviderException(e);
        }
    }

    private void validateSize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new RekognitionProviderException("IMAGE_EMPTY", "Image bytes are empty");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new RekognitionProviderException("IMAGE_TOO_LARGE",
                    "Image exceeds Rekognition 5 MB byte limit (" + imageBytes.length + " bytes)");
        }
        if (!isSupportedImageFormat(imageBytes)) {
            throw new RekognitionProviderException("UNSUPPORTED_IMAGE_FORMAT",
                    "Image format not supported. Only JPEG and PNG are accepted.");
        }
    }

    private boolean isSupportedImageFormat(byte[] bytes) {
        if (bytes.length < 4) return false;
        // JPEG: starts with 0xFF 0xD8
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return true;
        // PNG: starts with 0x89 0x50 0x4E 0x47
        if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47) return true;
        return false;
    }

    private Image toImage(byte[] bytes) {
        return Image.builder().bytes(SdkBytes.fromByteArray(bytes)).build();
    }

    private RekognitionProviderException toProviderException(Exception e) {
        if (e instanceof RekognitionException rekognitionException) {
            String code = rekognitionException.awsErrorDetails() != null
                    ? rekognitionException.awsErrorDetails().errorCode()
                    : "UNKNOWN";
            log.warn("Rekognition API error [{}]: {}", code, rekognitionException.getMessage());
            boolean retryable = "ThrottlingException".equals(code)
                    || "ProvisionedThroughputExceededException".equals(code)
                    || "ServiceUnavailableException".equals(code)
                    || "InternalServerError".equals(code);
            return new RekognitionProviderException(code, rekognitionException.getMessage(), retryable);
        }

        if (e instanceof SdkClientException sdkClientException) {
            String message = sdkClientException.getMessage();
            String code = (message != null && message.contains("Unable to load credentials"))
                    ? "CREDENTIALS_UNAVAILABLE"
                    : "SDK_CLIENT_ERROR";
            log.error("Rekognition client error [{}]: {}", code, message);
            return new RekognitionProviderException(code, message, false);
        }

        log.error("Unexpected Rekognition client error: {}", e.getMessage());
        return new RekognitionProviderException("SDK_CLIENT_ERROR", e.getMessage(), false);
    }
}
