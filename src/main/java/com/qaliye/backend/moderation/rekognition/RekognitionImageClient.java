package com.qaliye.backend.moderation.rekognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.Attribute;
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

    private void validateSize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new RekognitionProviderException("IMAGE_EMPTY", "Image bytes are empty");
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            throw new RekognitionProviderException("IMAGE_TOO_LARGE",
                    "Image exceeds Rekognition 5 MB byte limit (" + imageBytes.length + " bytes)");
        }
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
