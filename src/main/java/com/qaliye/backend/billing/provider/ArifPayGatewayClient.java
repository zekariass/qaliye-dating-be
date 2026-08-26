package com.qaliye.backend.billing.provider;

import com.qaliye.backend.billing.BillingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ArifPay gateway client – scaffolded for future implementation.
 * All methods throw until ArifPay is fully integrated.
 */
@Component
public class ArifPayGatewayClient implements LocalOnlinePaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(ArifPayGatewayClient.class);

    private final BillingProperties billingProps;

    public ArifPayGatewayClient(BillingProperties billingProps) {
        this.billingProps = billingProps;
    }

    @Override
    public String getMethodCode() {
        return "arifpay";
    }

    @Override
    public boolean isConfigured() {
        String key = billingProps.getArifPay().getSecretKey();
        return key != null && !key.isBlank();
    }

    @Override
    public CheckoutResult createCheckout(String orderReference, int amountMinorUnits,
                                         String currency, String customerId, String returnUrl) {
        log.warn("ArifPay checkout called but not yet implemented – orderReference={}", orderReference);
        throw new UnsupportedOperationException("arifpay_not_implemented");
    }
}
