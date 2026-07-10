package com.qaliye.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

@Component
public class RevenueCatWebhookAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookAuthenticationFilter.class);

    private static final String UNAUTHORIZED_JSON =
            "{\"error\":\"unauthorized\",\"message\":\"Invalid or missing authorization header\",\"status\":401}";

    @Value("${revenuecat.webhook-authorization:}")
    private String expectedAuthorization;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || authHeader.isBlank()) {
            log.warn("RevenueCat webhook: Authorization header missing");
            sendUnauthorized(response);
            return;
        }

        if (expectedAuthorization == null || expectedAuthorization.isBlank()) {
            log.error("RevenueCat webhook: server authorization secret not configured");
            sendUnauthorized(response);
            return;
        }

        byte[] expectedBytes = expectedAuthorization.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = authHeader.getBytes(StandardCharsets.UTF_8);

        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            log.warn("RevenueCat webhook: Authorization header mismatch");
            sendUnauthorized(response);
            return;
        }

        PreAuthenticatedAuthenticationToken auth =
                new PreAuthenticatedAuthenticationToken("revenuecat-webhook", null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(UNAUTHORIZED_JSON);
    }
}
