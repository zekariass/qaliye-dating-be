package com.qaliye.backend.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Service
public class SupabaseAuthAdminClient {

    private static final Logger log = LoggerFactory.getLogger(SupabaseAuthAdminClient.class);

    private final RestClient restClient;
    private final String supabaseUrl;
    private final String serviceRoleKey;

    public SupabaseAuthAdminClient(RestClient restClient,
                                    @Value("${supabase.url}") String supabaseUrl,
                                    @Value("${supabase.service-role-key}") String serviceRoleKey) {
        this.restClient = restClient;
        this.supabaseUrl = supabaseUrl;
        this.serviceRoleKey = serviceRoleKey;
    }

    /**
     * Soft-deletes the Supabase Auth user via the Admin API
     * ({@code DELETE /auth/v1/admin/users/{id}} with {@code should_soft_delete: true}).
     *
     * <p>Supabase soft-deletion retains the {@code auth.users} row (preserving the FK from
     * {@code app_users}) while:
     * <ul>
     *   <li>anonymizing the email and phone so they can be reused on re-registration,</li>
     *   <li>removing all linked OAuth identities (Google, Apple …) from {@code auth.identities},</li>
     *   <li>revoking all active sessions and MFA factors.</li>
     * </ul>
     *
     * <p>A 404 is treated as idempotent — the user was already soft-deleted or never existed.
     *
     * @throws AuthUserDeletionException on any non-404 4xx or 5xx response, or network failure.
     */
    public void softDeleteAuthUser(UUID userId) {
        try {
            restClient.method(HttpMethod.DELETE)
                    .uri(supabaseUrl + "/auth/v1/admin/users/" + userId)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .header("apikey", serviceRoleKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("should_soft_delete", true))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                        if (resp.getStatusCode().value() == 404) {
                            log.info("Auth user {} not found during soft-delete (already removed)", userId);
                        } else {
                            throw new AuthUserDeletionException(
                                    "Supabase Auth returned " + resp.getStatusCode() + " for user " + userId);
                        }
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                        throw new AuthUserDeletionException(
                                "Supabase Auth returned " + resp.getStatusCode() + " for user " + userId);
                    })
                    .toBodilessEntity();
            log.info("Auth user soft-deleted: {}", userId);
        } catch (AuthUserDeletionException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthUserDeletionException(
                    "Failed to soft-delete auth user " + userId + ": " + e.getMessage(), e);
        }
    }

    public static class AuthUserDeletionException extends RuntimeException {
        public AuthUserDeletionException(String message) {
            super(message);
        }
        public AuthUserDeletionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
