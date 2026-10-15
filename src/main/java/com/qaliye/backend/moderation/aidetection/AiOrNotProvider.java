package com.qaliye.backend.moderation.aidetection;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Iterator;
import java.util.Map;

/**
 * {@link AiDetectionProvider} backed by the AI or Not v2 API.
 *
 * <p>API: {@code POST https://api.aiornot.com/v2/image/sync?only=ai_generated}
 * <br>Auth: {@code Authorization: Bearer {API_KEY}}
 * <br>Body: {@code multipart/form-data} with {@code image} file field.
 */
public class AiOrNotProvider implements AiDetectionProvider {

    private static final Logger log = LoggerFactory.getLogger(AiOrNotProvider.class);

    private final AiDetectionProperties props;
    private final RestClient restClient;

    public AiOrNotProvider(AiDetectionProperties props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    @Override
    public AiDetectionResult analyze(byte[] imageBytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new ByteArrayResource(imageBytes) {
            @Override public String getFilename() { return "image.jpeg"; }
        });

        JsonNode root;
        try {
            root = restClient.post()
                    .uri(props.getAiOrNot().getBaseUrl() + "?only=ai_generated")
                    .header("Authorization", "Bearer " + props.getAiOrNot().getApiKey())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, resp) -> {
                        String body = "";
                        try (var is = resp.getBody()) {
                            body = new String(is.readAllBytes());
                        } catch (Exception ignored) {}
                        log.error("AI or Not API error {}: {}", resp.getStatusCode(), body);
                        throw new RuntimeException("AI or Not API returned " + resp.getStatusCode());
                    })
                    .body(JsonNode.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI or Not request failed: {}", e.getMessage());
            throw new RuntimeException("AI detection request failed", e);
        }

        if (root == null) {
            throw new RuntimeException("AI or Not: null response body");
        }

        JsonNode aiGen = root.path("report").path("ai_generated");
        if (aiGen.isMissingNode() || aiGen.isNull()) {
            log.warn("AI or Not: unexpected response structure — {}", root);
            throw new RuntimeException("AI or Not: 'ai_generated' section missing in response");
        }

        boolean rawDetected = aiGen.path("ai").path("is_detected").asBoolean(false);
        double  confidence  = aiGen.path("ai").path("confidence").asDouble(0.0);
        String  verdict     = aiGen.path("verdict").asText(null);

        String topGenerator = null;
        JsonNode generatorNode = aiGen.path("generator");
        if (generatorNode.isObject()) {
            double maxConf = -1;
            Iterator<Map.Entry<String, JsonNode>> fields = generatorNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                double val = entry.getValue().path("confidence").asDouble(0.0);
                if (val > maxConf) {
                    maxConf = val;
                    topGenerator = entry.getKey();
                }
            }
        }

        boolean detected = rawDetected && confidence >= props.getConfidenceThreshold();
        AiDetectionAction action = detected ? props.getAction() : AiDetectionAction.ADVISE_ONLY;

        log.debug("AI or Not: verdict={} confidence={} detected={} generator={}", verdict, confidence, detected, topGenerator);

        return new AiDetectionResult(detected, confidence, topGenerator, verdict, action);
    }
}
