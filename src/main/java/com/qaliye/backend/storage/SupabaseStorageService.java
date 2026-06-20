package com.qaliye.backend.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class SupabaseStorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service-role-key}")
    private String serviceRoleKey;

    private final RestClient restClient;

    public SupabaseStorageService(RestClient restClient) {
        this.restClient = restClient;
    }

    public void uploadFile(String bucket, String path, byte[] content, String contentType) {
        restClient.post()
                .uri(supabaseUrl + "/storage/v1/object/" + bucket + "/" + path)
                .header("Authorization", "Bearer " + serviceRoleKey)
                .header("x-upsert", "true")
                .contentType(org.springframework.http.MediaType.parseMediaType(contentType))
                .body(content)
                .retrieve()
                .toBodilessEntity();
    }

    public byte[] downloadPhoto(String storagePath) {
        try {
            return restClient.get()
                    .uri(supabaseUrl + "/storage/v1/object/" + storagePath)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception e) {
            log.error("Failed to download photo {}: {}", storagePath, e.getMessage());
            return null;
        }
    }

    public void deleteObject(String bucket, String storagePath) {
        try {
            restClient.delete()
                    .uri(supabaseUrl + "/storage/v1/object/" + bucket + "/" + storagePath)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to delete storage object {}/{}: {}", bucket, storagePath, e.getMessage());
        }
    }

    public String generateSignedUrl(String bucket, String path, int expiresInSeconds) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri(supabaseUrl + "/storage/v1/object/sign/" + bucket + "/" + path)
                    .header("Authorization", "Bearer " + serviceRoleKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("expiresIn", expiresInSeconds))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});

            if (response == null) return null;
            Object signedUrl = response.get("signedURL");
            if (signedUrl == null) return null;
            String url = signedUrl.toString();
            return url.startsWith("/") ? supabaseUrl + "/storage/v1" + url : url;
        } catch (Exception e) {
            log.error("Failed to generate signed URL for {}/{}: {}", bucket, path, e.getMessage());
            return null;
        }
    }
}
