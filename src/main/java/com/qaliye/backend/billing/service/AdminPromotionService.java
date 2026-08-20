package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.dto.CampaignDto;
import com.qaliye.backend.billing.dto.CreateCampaignRequest;
import com.qaliye.backend.billing.dto.RedemptionDto;
import com.qaliye.backend.billing.dto.UpdateCampaignRequest;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminPromotionService {

    private static final Logger log = LoggerFactory.getLogger(AdminPromotionService.class);

    private final PromotionRepository promotionRepo;

    public AdminPromotionService(PromotionRepository promotionRepo) {
        this.promotionRepo = promotionRepo;
    }

    @Transactional
    public CampaignDto createCampaign(CreateCampaignRequest req, UUID adminUserId) {
        validateCreateRequest(req);

        PromotionRepository.CampaignRow proto = new PromotionRepository.CampaignRow(
                null, req.campaignKey(), req.name(), req.description(),
                req.triggerType(), req.eligibilityType(), req.benefitType(),
                req.discountType(), req.discountValue(), req.discountCurrency(),
                req.subscriptionProductId(), req.consumableProductId(), req.countryCode(),
                req.durationDays(), req.newUserWindowDays(),
                req.maxRedemptions(),
                req.maxRedemptionsPerUser() != null ? req.maxRedemptionsPerUser() : 1,
                0, 0,
                req.priority() != null ? req.priority() : 0,
                req.startsAt(), req.endsAt(),
                "DRAFT", req.targetGender(), req.includedCredits(), adminUserId, null, null
        );

        UUID id = promotionRepo.insertCampaign(proto, adminUserId);
        return toCampaignDto(promotionRepo.findCampaignById(id)
                .orElseThrow(() -> new IllegalStateException("Campaign not found after insert: " + id)));
    }

    public CampaignDto getCampaign(UUID id) {
        return toCampaignDto(promotionRepo.findCampaignById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "campaign_not_found")));
    }

    public Map<String, Object> listCampaigns(String status, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safeSize;
        List<CampaignDto> campaigns = promotionRepo.listCampaigns(status, safeSize, offset)
                .stream().map(this::toCampaignDto).toList();
        long total = promotionRepo.countCampaigns(status);
        int totalPages = (int) Math.ceil((double) total / safeSize);
        return Map.of("campaigns", campaigns, "page", safePage,
                "pageSize", safeSize, "total", total, "totalPages", totalPages);
    }

    @Transactional
    public CampaignDto updateCampaign(UUID id, UpdateCampaignRequest req) {
        promotionRepo.findCampaignById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "campaign_not_found"));
        promotionRepo.updateCampaign(id, req.name(), req.description(),
                req.maxRedemptions(), req.maxRedemptionsPerUser(), req.priority(), req.endsAt(),
                req.targetGender());
        return toCampaignDto(promotionRepo.findCampaignById(id).orElseThrow());
    }

    @Transactional
    public CampaignDto activateCampaign(UUID id) {
        return transitionStatus(id, "ACTIVE", List.of("DRAFT", "PAUSED"));
    }

    @Transactional
    public CampaignDto pauseCampaign(UUID id) {
        return transitionStatus(id, "PAUSED", List.of("ACTIVE"));
    }

    @Transactional
    public CampaignDto expireCampaign(UUID id) {
        return transitionStatus(id, "EXPIRED", List.of("ACTIVE", "PAUSED", "DRAFT"));
    }

    public List<RedemptionDto> listRedemptions(UUID campaignId, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, pageSize));
        int offset = (safePage - 1) * safeSize;
        return promotionRepo.listRedemptionsByCampaign(campaignId, safeSize, offset)
                .stream().map(this::toRedemptionDto).toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private CampaignDto transitionStatus(UUID id, String newStatus, List<String> allowedFrom) {
        PromotionRepository.CampaignRow c = promotionRepo.findCampaignById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "campaign_not_found"));
        if (!allowedFrom.contains(c.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid_status_transition: " + c.status() + " -> " + newStatus);
        }
        promotionRepo.updateCampaignStatus(id, newStatus);
        log.info("Campaign {} status: {} -> {} ", id, c.status(), newStatus);
        return toCampaignDto(promotionRepo.findCampaignById(id).orElseThrow());
    }

    private void validateCreateRequest(CreateCampaignRequest req) {
        if (req.campaignKey() == null || req.campaignKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "campaign_key_required");
        }
        boolean isCredits = "CREDITS".equals(req.benefitType());
        if (req.triggerType() == null || req.eligibilityType() == null
                || req.benefitType() == null
                || (!isCredits && req.subscriptionProductId() == null && req.consumableProductId() == null)
                || (req.subscriptionProductId() != null && req.consumableProductId() != null)
                || (isCredits && (req.subscriptionProductId() != null || req.consumableProductId() != null))
                || req.countryCode() == null || req.startsAt() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing_or_conflicting_required_fields");
        }
        if ("PURCHASE".equals(req.triggerType())) {
            if (!"FREE_PREMIUM".equals(req.benefitType()) && !"DISCOUNT".equals(req.benefitType())
                    && !"CREDITS".equals(req.benefitType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_benefit_type");
            }
        }
        if ("AUTO_ON_SIGNUP".equals(req.triggerType())) {
            if (!"FREE_PREMIUM".equals(req.benefitType()) && !"CREDITS".equals(req.benefitType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_benefit_type");
            }
        }
        if ("FREE_PREMIUM".equals(req.benefitType())) {
            if (req.durationDays() == null || req.durationDays() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "duration_days_required");
            }
        }
        if ("DISCOUNT".equals(req.benefitType())) {
            if (req.discountType() == null || req.discountValue() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discount_fields_required");
            }
        }
        if ("CREDITS".equals(req.benefitType())) {
            if (req.includedCredits() == null || req.includedCredits() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "included_credits_required");
            }
        }
        if ("NEW_USER".equals(req.eligibilityType())) {
            if (req.newUserWindowDays() == null || req.newUserWindowDays() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "new_user_window_days_required");
            }
        }
        if (req.endsAt() != null && !req.endsAt().isAfter(req.startsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ends_at_must_be_after_starts_at");
        }
        if (req.targetGender() != null
                && !"MALE".equals(req.targetGender()) && !"FEMALE".equals(req.targetGender())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_target_gender");
        }
    }

    private CampaignDto toCampaignDto(PromotionRepository.CampaignRow c) {
        return new CampaignDto(
                c.id(), c.campaignKey(), c.name(), c.description(),
                c.triggerType(), c.eligibilityType(), c.benefitType(),
                c.discountType(), c.discountValue(), c.discountCurrency(),
                c.subscriptionProductId(), c.consumableProductId(), c.countryCode(),
                c.durationDays(), c.newUserWindowDays(),
                c.maxRedemptions(), c.maxRedemptionsPerUser(),
                c.reservedCount(), c.fulfilledCount(),
                c.priority(), c.startsAt(), c.endsAt(),
                c.status(), c.targetGender(), c.includedCredits(), c.createdAt(), c.updatedAt()
        );
    }

    private RedemptionDto toRedemptionDto(PromotionRepository.RedemptionRow r) {
        return new RedemptionDto(
                r.id(), r.campaignId(), null, r.userId(), r.subscriptionId(),
                r.paymentOrderId(), r.status(), r.eligibilityCountry(), r.eligibilityGender(),
                r.originalAmountMinor(), r.discountAmountMinor(), r.finalAmountMinor(),
                r.currency(), r.reservedAt(), r.fulfilledAt(),
                r.cancelledAt(), r.expiredAt(), r.failureCode()
        );
    }
}
