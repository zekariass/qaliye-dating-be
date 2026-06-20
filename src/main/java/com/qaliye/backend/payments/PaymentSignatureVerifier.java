package com.qaliye.backend.payments;

import jakarta.servlet.http.HttpServletRequest;

public interface PaymentSignatureVerifier {
    boolean verify(HttpServletRequest request, byte[] body);
}
