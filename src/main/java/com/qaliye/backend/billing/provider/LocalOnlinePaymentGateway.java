package com.qaliye.backend.billing.provider;

/**
 * Contract for local online payment gateway clients (Chapa, ArifPay, etc.).
 * Each gateway implementation resolves to a single {@code methodCode} that
 * matches the {@code payment_methods.method_code} column.
 */
public interface LocalOnlinePaymentGateway {

    String getMethodCode();

    boolean isConfigured();

    CheckoutResult createCheckout(String orderReference, int amountMinorUnits,
                                  String currency, String customerId);

    record CheckoutResult(String checkoutUrl, String txRef) {}
}
