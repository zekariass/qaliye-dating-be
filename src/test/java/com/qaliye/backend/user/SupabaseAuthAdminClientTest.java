package com.qaliye.backend.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SupabaseAuthAdminClientTest {

    private static final String SUPABASE_URL = "https://test.supabase.co";
    private static final String SERVICE_ROLE_KEY = "test-service-role-key";

    MockRestServiceServer server;
    SupabaseAuthAdminClient client;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new SupabaseAuthAdminClient(
                RestClient.builder().requestFactory(restTemplate.getRequestFactory()).build(),
                SUPABASE_URL,
                SERVICE_ROLE_KEY
        );
    }

    @Test
    void softDeleteAuthUser_success_noExceptionThrown() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andRespond(withSuccess());

        assertThatCode(() -> client.softDeleteAuthUser(userId)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void softDeleteAuthUser_notFound_isIdempotentNoException() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatCode(() -> client.softDeleteAuthUser(userId)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void softDeleteAuthUser_unauthorized_throwsException() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.softDeleteAuthUser(userId))
                .isInstanceOf(SupabaseAuthAdminClient.AuthUserDeletionException.class)
                .hasMessageContaining("401");
        server.verify();
    }

    @Test
    void softDeleteAuthUser_unprocessableEntity_throwsException() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));

        assertThatThrownBy(() -> client.softDeleteAuthUser(userId))
                .isInstanceOf(SupabaseAuthAdminClient.AuthUserDeletionException.class)
                .hasMessageContaining("422");
        server.verify();
    }

    @Test
    void softDeleteAuthUser_serverError_throwsException() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andRespond(withServerError());

        assertThatThrownBy(() -> client.softDeleteAuthUser(userId))
                .isInstanceOf(SupabaseAuthAdminClient.AuthUserDeletionException.class);
        server.verify();
    }

    @Test
    void softDeleteAuthUser_sendsAuthorizationAndApiKeyHeaders() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andExpect(header("Authorization", "Bearer " + SERVICE_ROLE_KEY))
              .andExpect(header("apikey", SERVICE_ROLE_KEY))
              .andRespond(withSuccess());

        client.softDeleteAuthUser(userId);
        server.verify();
    }

    @Test
    void softDeleteAuthUser_sendsShouldSoftDeleteBody() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andExpect(content().contentType(MediaType.APPLICATION_JSON))
              .andExpect(content().string(containsString("should_soft_delete")))
              .andRespond(withSuccess());

        client.softDeleteAuthUser(userId);
        server.verify();
    }

    @Test
    void softDeleteAuthUser_serviceRoleKeyNotIncludedInExceptionMessage() {
        server.expect(requestTo(SUPABASE_URL + "/auth/v1/admin/users/" + userId))
              .andExpect(method(HttpMethod.DELETE))
              .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.softDeleteAuthUser(userId))
                .isInstanceOf(SupabaseAuthAdminClient.AuthUserDeletionException.class)
                .hasMessageNotContaining(SERVICE_ROLE_KEY);
        server.verify();
    }
}
