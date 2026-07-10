package com.qaliye.backend.auth.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SMS provider backed by AfroMessage.
 *
 * <p>API: POST {base-url}
 * <br>Auth: {@code Authorization: Bearer {token}}
 * <br>Body: {@code {"from":"<optional>","sender":"...","to":"+251...","message":"..."}}
 * <br>Success: HTTP 200 with {@code "acknowledge":"success"} in the JSON response body.
 */
public class AfroMessageSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(AfroMessageSmsProvider.class);

    private final SmsProperties.AfroMessage props;
    private final RestClient restClient;

    public AfroMessageSmsProvider(SmsProperties.AfroMessage props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    @Override
    public void send(String to, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        if (props.getIdentifierId() != null && !props.getIdentifierId().isBlank()) {
            body.put("from", props.getIdentifierId());
        }
        if (props.getSenderId() != null && !props.getSenderId().isBlank()) {
            body.put("sender", props.getSenderId());
        }
        body.put("to", to);
        body.put("message", message);

        log.debug("AfroMessage send request: to={}, sender={}", to, props.getSenderId());

        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri(props.getBaseUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.getToken())
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        String respBody = "";
                        try (var is = resp.getBody()) {
                            respBody = new String(is.readAllBytes());
                        } catch (Exception ignored) {}
                        log.error("AfroMessage returned HTTP {}: body={}", resp.getStatusCode(), respBody);
                        throw new SmsDeliveryException("AfroMessage HTTP " + resp.getStatusCode());
                    })
                    .body(Map.class);
        } catch (SmsDeliveryException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("AfroMessage HTTP request failed: {}", e.getMessage());
            throw new SmsDeliveryException("AfroMessage request failed", e);
        }

        if (response == null) {
            log.error("AfroMessage returned empty response");
            throw new SmsDeliveryException("AfroMessage returned empty response");
        }

        Object acknowledge = response.get("acknowledge");
        if (!"success".equals(acknowledge)) {
            Object responseObj = response.get("response");
            String errorDetail = "";
            if (responseObj instanceof Map<?, ?> respMap) {
                Object errors = respMap.get("errors");
                if (errors != null) {
                    errorDetail = errors.toString();
                }
            }
            log.warn("AfroMessage rejected SMS: acknowledge={}, errors={}", acknowledge, errorDetail);
            throw new SmsDeliveryException("AfroMessage did not acknowledge success");
        }
    }
}
