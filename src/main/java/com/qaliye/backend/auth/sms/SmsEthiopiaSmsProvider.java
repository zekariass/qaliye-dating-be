package com.qaliye.backend.auth.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SMS provider backed by SMS Ethiopia.
 *
 * <p>API: POST {base-url}/sms/send
 * <br>Auth: {@code KEY: {api-key}} header
 * <br>Body: {@code {"msisdn":"251...","text":"..."}} (phone number without leading +)
 * <br>Success: HTTP 200 with {@code "status":"success"} in the JSON response body.
 */
public class SmsEthiopiaSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(SmsEthiopiaSmsProvider.class);

    private final SmsProperties.SmsEthiopia props;
    private final RestClient restClient;

    public SmsEthiopiaSmsProvider(SmsProperties.SmsEthiopia props, RestClient restClient) {
        this.props = props;
        this.restClient = restClient;
    }

    @Override
    public void send(String to, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("msisdn", PhoneNormalizer.stripPlus(to));
        body.put("text", message);

        Map<?, ?> response;
        try {
            response = restClient.post()
                    .uri(props.getBaseUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("KEY", props.getApiKey())
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), (req, resp) -> {
                        String respBody = "";
                        try (var is = resp.getBody()) {
                            respBody = new String(is.readAllBytes());
                        } catch (Exception ignored) {}
                        log.error("SMS Ethiopia returned HTTP {}: body={}", resp.getStatusCode(), respBody);
                        throw new SmsDeliveryException("SMS Ethiopia HTTP " + resp.getStatusCode());
                    })
                    .body(Map.class);
        } catch (SmsDeliveryException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("SMS Ethiopia HTTP request failed: {}", e.getMessage());
            throw new SmsDeliveryException("SMS Ethiopia request failed", e);
        }

        log.debug("SMS Ethiopia response: {}", response);

        if (response != null) {
            Object errorMessage = response.get("error_message");
            Object error = response.get("error");
            if (errorMessage != null || error != null) {
                log.warn("SMS Ethiopia rejected SMS: error_message={}, error={}", errorMessage, error);
                throw new SmsDeliveryException("SMS Ethiopia returned an error");
            }
        }
    }
}
