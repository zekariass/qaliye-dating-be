package com.qaliye.backend.billing.provider;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves the correct {@link LocalOnlinePaymentGateway} for a given method code.
 */
@Component
public class LocalGatewayRegistry {

    private final Map<String, LocalOnlinePaymentGateway> byMethodCode;

    public LocalGatewayRegistry(List<LocalOnlinePaymentGateway> gateways) {
        this.byMethodCode = gateways.stream()
                .collect(Collectors.toMap(LocalOnlinePaymentGateway::getMethodCode, Function.identity()));
    }

    public LocalOnlinePaymentGateway resolve(String methodCode) {
        LocalOnlinePaymentGateway gateway = byMethodCode.get(methodCode);
        if (gateway == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported_online_payment_method: " + methodCode);
        }
        if (!gateway.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "payment_provider_not_configured");
        }
        return gateway;
    }

    public boolean supports(String methodCode) {
        return byMethodCode.containsKey(methodCode);
    }
}
