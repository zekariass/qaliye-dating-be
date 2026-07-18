package com.qaliye.backend.moderation;

import com.qaliye.backend.moderation.rekognition.RekognitionImageClient;
import com.qaliye.backend.moderation.rekognition.RekognitionProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectFacesRequest;
import software.amazon.awssdk.services.rekognition.model.DetectFacesResponse;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectModerationLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;
import software.amazon.awssdk.services.rekognition.model.RekognitionException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RekognitionImageClientTest {

    @Mock RekognitionClient awsClient;

    RekognitionImageClient client;

    @BeforeEach
    void setUp() {
        client = new RekognitionImageClient(awsClient);
    }

    @Test
    void detectFaces_returns_face_list() {
        FaceDetail face = FaceDetail.builder().confidence(99f).build();
        when(awsClient.detectFaces(any(DetectFacesRequest.class)))
                .thenReturn(DetectFacesResponse.builder().faceDetails(face).build());

        List<FaceDetail> faces = client.detectFaces(new byte[100], false);

        assertThat(faces).hasSize(1);
        assertThat(faces.get(0).confidence()).isEqualTo(99f);
    }

    @Test
    void detectModerationLabels_returns_empty_for_clean_image() {
        when(awsClient.detectModerationLabels(any(DetectModerationLabelsRequest.class)))
                .thenReturn(DetectModerationLabelsResponse.builder().build());

        assertThat(client.detectModerationLabels(new byte[100], 90f)).isEmpty();
    }

    @Test
    void throws_provider_exception_for_null_bytes() {
        assertThatThrownBy(() -> client.detectFaces(null, false))
                .isInstanceOf(RekognitionProviderException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void throws_provider_exception_for_oversized_image() {
        byte[] tooLarge = new byte[(int) (RekognitionImageClient.MAX_IMAGE_BYTES + 1)];

        assertThatThrownBy(() -> client.detectFaces(tooLarge, false))
                .isInstanceOf(RekognitionProviderException.class)
                .extracting(e -> ((RekognitionProviderException) e).getErrorCode())
                .isEqualTo("IMAGE_TOO_LARGE");
    }

    @Test
    void wraps_throttling_exception_as_retryable() {
        RekognitionException throttled = (RekognitionException) RekognitionException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ThrottlingException").build())
                .message("Rate exceeded")
                .build();
        when(awsClient.detectFaces(any(DetectFacesRequest.class))).thenThrow(throttled);

        assertThatThrownBy(() -> client.detectFaces(new byte[100], false))
                .isInstanceOf(RekognitionProviderException.class)
                .satisfies(e -> assertThat(((RekognitionProviderException) e).isRetryable()).isTrue());
    }
}
