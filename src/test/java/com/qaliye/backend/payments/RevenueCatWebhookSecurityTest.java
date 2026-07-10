package com.qaliye.backend.payments;

import com.qaliye.backend.billing.service.RevenueCatWebhookHandler;
import com.qaliye.backend.config.RevenueCatWebhookAuthenticationFilter;
import com.qaliye.backend.config.UserStatusFilter;
import com.qaliye.backend.user.UserStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {RevenueCatWebhookSecurityTest.TestConfig.class}, webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {"revenuecat.webhook-authorization=Bearer test-static-secret-12345", "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://test.jwks.uri"})
class RevenueCatWebhookSecurityTest {

    private static final String EXPECTED_AUTH = "Bearer test-static-secret-12345";

    @Autowired
    WebApplicationContext wac;

    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    PaymentService paymentService;
    @MockitoBean
    StripeSignatureVerifier stripeSignatureVerifier;
    @MockitoBean
    RevenueCatSignatureVerifier revenueCatSignatureVerifier;
    @MockitoBean
    UserStatusService userStatusService;
    @MockitoBean
    RevenueCatWebhookHandler revenueCatWebhookHandler;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(jwtDecoder, paymentService, stripeSignatureVerifier, revenueCatSignatureVerifier, userStatusService, revenueCatWebhookHandler);
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    void correctStaticSecret_succeeds_onRevenueCatRoute() throws Exception {
        when(revenueCatSignatureVerifier.verify(any(), any())).thenReturn(true);
        when(paymentService.logAndCheck(any(), any(), any(), any())).thenReturn(true);
        mockMvc.perform(post("/api/v1/payments/webhooks/revenuecat").header("Authorization", EXPECTED_AUTH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        verify(jwtDecoder, never()).decode(any());
    }

    @Test
    void missingAuthorizationHeader_returns401_onRevenueCatRoute() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhooks/revenuecat").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verify(jwtDecoder, never()).decode(any());
    }

    @Test
    void wrongBearerValue_returns401_onRevenueCatRoute() throws Exception {
        mockMvc.perform(post("/api/v1/payments/webhooks/revenuecat").header("Authorization", "Bearer wrong-secret-99999").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        verify(jwtDecoder, never()).decode(any());
    }

    @Test
    void revenueCatRoute_doesNotInvokeJwtDecoder() throws Exception {
        when(revenueCatSignatureVerifier.verify(any(), any())).thenReturn(true);
        when(paymentService.logAndCheck(any(), any(), any(), any())).thenReturn(true);
        mockMvc.perform(post("/api/v1/payments/webhooks/revenuecat").header("Authorization", EXPECTED_AUTH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        verify(jwtDecoder, never()).decode(any());
    }

    @Test
    void otherProtectedEndpoint_withoutJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/some-protected-endpoint")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherProtectedEndpoint_withValidJwt_succeeds() throws Exception {
        UUID userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("valid-jwt-token").header("alg", "ES256").subject(userId.toString()).build();
        when(jwtDecoder.decode(any())).thenReturn(jwt);
        when(userStatusService.getStatus(any())).thenReturn(new UserStatusService.UserStatus("ACTIVE", "USER", "en"));
        mockMvc.perform(get("/api/v1/some-protected-endpoint").header("Authorization", "Bearer valid-jwt-token")).andExpect(status().isNotFound());
        verify(jwtDecoder, atLeastOnce()).decode(any());
    }

    @Configuration
    @EnableWebSecurity
    static class TestConfig {

        @Bean
        @Order(1)
        SecurityFilterChain revenueCatWebhookSecurityFilterChain(HttpSecurity http, RevenueCatWebhookAuthenticationFilter webhookAuthFilter) throws Exception {
            http.securityMatcher("/api/v1/payments/webhooks/revenuecat").csrf(AbstractHttpConfigurer::disable).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(auth -> auth.anyRequest().authenticated()).addFilterBefore(webhookAuthFilter, UsernamePasswordAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        @Order(2)
        SecurityFilterChain filterChain(HttpSecurity http, UserStatusFilter userStatusFilter) throws Exception {
            http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/health").permitAll().anyRequest().authenticated()).oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {})).addFilterAfter(userStatusFilter, BearerTokenAuthenticationFilter.class);
            return http.build();
        }

        @Bean
        RevenueCatWebhookAuthenticationFilter revenueCatWebhookAuthenticationFilter() {
            return new RevenueCatWebhookAuthenticationFilter();
        }

        @Bean
        UserStatusFilter userStatusFilter(UserStatusService userStatusService) {
            return new UserStatusFilter(userStatusService);
        }

        @Bean
        PaymentWebhookController paymentWebhookController(StripeSignatureVerifier stripeSignatureVerifier, RevenueCatSignatureVerifier revenueCatSignatureVerifier, PaymentService paymentService, RevenueCatWebhookHandler revenueCatWebhookHandler) {
            return new PaymentWebhookController(stripeSignatureVerifier, revenueCatSignatureVerifier, paymentService, revenueCatWebhookHandler, new com.fasterxml.jackson.databind.ObjectMapper());
        }
    }
}