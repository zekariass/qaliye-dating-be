package com.qaliye.backend.discovery.service;

import com.qaliye.backend.discovery.config.StorageProperties;
import com.qaliye.backend.discovery.dto.DiscoveryPhotoDto;
import com.qaliye.backend.storage.SupabaseStorageService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class StorageSigningService {

    private final SupabaseStorageService supabaseStorageService;
    private final StorageProperties storageProperties;

    public StorageSigningService(SupabaseStorageService supabaseStorageService,
                                 StorageProperties storageProperties) {
        this.supabaseStorageService = supabaseStorageService;
        this.storageProperties = storageProperties;
    }

    public int getTtlSeconds() {
        return storageProperties.getSignedUrl().ttlSeconds();
    }

    public String sign(String bucket, String path) {
        return supabaseStorageService.generateSignedUrl(bucket, path, getTtlSeconds());
    }

    public DiscoveryPhotoDto signPhoto(UUID photoId, int order, boolean isPrimary,
                                       String bucket, String path) {
        String signedUrl = sign(bucket, path);
        Instant expiresAt = Instant.now().plusSeconds(getTtlSeconds());
        return new DiscoveryPhotoDto(photoId, order, isPrimary, signedUrl, expiresAt);
    }
}
