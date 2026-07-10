package com.qaliye.backend.auth.sms;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PhoneNormalizer {

    private static final String ETHIOPIA_PREFIX = "+251";
    private static final String ETHIOPIA_CC_NO_PLUS = "251";
    private static final int ETHIOPIA_E164_LENGTH = 13;

    private PhoneNormalizer() {}

    /**
     * Normalizes a phone number to Ethiopian E.164 format ({@code +251XXXXXXXXX}).
     * Accepts:
     * <ul>
     *   <li>{@code +251911234567}</li>
     *   <li>{@code 251911234567}</li>
     *   <li>{@code 0911234567}</li>
     *   <li>{@code 911234567}</li>
     * </ul>
     * Throws {@link ResponseStatusException} 400 for unsupported/invalid numbers.
     */
    public static String normalizeEthiopian(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_phone");
        }

        String stripped = phone.strip().replaceAll("[\\s\\-()\\.]", "");

        if (stripped.startsWith(ETHIOPIA_PREFIX)) {
            validateE164(stripped);
            return stripped;
        }

        String digitsOnly = stripped.replaceAll("[^0-9]", "");

        String normalized;
        if (digitsOnly.startsWith(ETHIOPIA_CC_NO_PLUS) && digitsOnly.length() == 12) {
            normalized = "+" + digitsOnly;
        } else if (digitsOnly.startsWith("0") && digitsOnly.length() == 10) {
            normalized = ETHIOPIA_PREFIX + digitsOnly.substring(1);
        } else if (digitsOnly.startsWith("9") && digitsOnly.length() == 9) {
            normalized = ETHIOPIA_PREFIX + digitsOnly;
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_phone_country_code");
        }

        validateE164(normalized);
        return normalized;
    }

    private static void validateE164(String phone) {
        if (phone.length() != ETHIOPIA_E164_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_phone_number");
        }
        String digits = phone.substring(4);
        for (char c : digits.toCharArray()) {
            if (!Character.isDigit(c)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_phone_number");
            }
        }
    }

    /**
     * @deprecated Use {@link #normalizeEthiopian(String)} which validates and normalizes.
     */
    @Deprecated
    public static void validateEthiopian(String phone) {
        validateE164(phone);
    }

    /**
     * Strips the leading {@code +} from an E.164 number.
     * e.g. {@code +251911234567} → {@code 251911234567}
     */
    public static String stripPlus(String e164) {
        return e164.startsWith("+") ? e164.substring(1) : e164;
    }
}
