package com.qaliye.backend.payments;

import com.qaliye.backend.config.RevenueCatWebhookAuthenticationFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RevenueCatWebhookFilterTest {

    private static final String EXPECTED_AUTH = "Bearer test-static-secret-12345";

    @Mock
    FilterChain filterChain;

    private RevenueCatWebhookAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RevenueCatWebhookAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "expectedAuthorization", EXPECTED_AUTH);
    }

    @Test
    void correctStaticSecret_succeeds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/revenuecat");
        request.addHeader("Authorization", EXPECTED_AUTH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void missingAuthorizationHeader_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/revenuecat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assert response.getStatus() == 401;
    }

    @Test
    void wrongBearerValue_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/revenuecat");
        request.addHeader("Authorization", "Bearer wrong-secret-99999");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assert response.getStatus() == 401;
    }

    @Test
    void filter_doesNotInvokeJwtDecoder_evenWithJwtLikeToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/revenuecat");
        request.addHeader("Authorization", EXPECTED_AUTH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void emptyServerSecret_returns401() throws Exception {
        RevenueCatWebhookAuthenticationFilter emptyFilter = new RevenueCatWebhookAuthenticationFilter();
        ReflectionTestUtils.setField(emptyFilter, "expectedAuthorization", "");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments/webhooks/revenuecat");
        request.addHeader("Authorization", EXPECTED_AUTH);
        MockHttpServletResponse response = new MockHttpServletResponse();

        emptyFilter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        assert response.getStatus() == 401;
    }
}
