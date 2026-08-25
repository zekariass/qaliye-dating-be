package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.dto.ClaimablePromotionDto;
import com.qaliye.backend.billing.dto.OfferDto;
import com.qaliye.backend.billing.dto.PaymentChannelDto;
import com.qaliye.backend.billing.dto.PaymentChannelsResponse;
import com.qaliye.backend.billing.dto.PaymentMethodDto;
import com.qaliye.backend.billing.dto.PaymentOptionsResponse;
import com.qaliye.backend.billing.dto.PromotionDto;
import com.qaliye.backend.billing.repository.BillingRepository;
import com.qaliye.backend.billing.repository.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);
    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private final BillingRepository billingRepo;
    private final BillingMarketResolver marketResolver;
    private final PromotionEligibilityService promotionEligibilityService;
    private final CountrySettingsService countrySettingsService;

    public OfferService(BillingRepository billingRepo,
                        BillingMarketResolver marketResolver,
                        PromotionEligibilityService promotionEligibilityService,
                        CountrySettingsService countrySettingsService) {
        this.billingRepo = billingRepo;
        this.marketResolver = marketResolver;
        this.promotionEligibilityService = promotionEligibilityService;
        this.countrySettingsService = countrySettingsService;
    }

    public PaymentOptionsResponse getPaymentOptions(UUID userId, String platform) {
        BillingMarketResolver.MarketResult market = marketResolver.resolveMethodsMarket(userId, platform);

        List<PaymentMethodDto> methods = billingRepo
                .findActivePaymentMethods(market.resolvedCountryCode(), market.platform())
                .stream()
                .map(this::toPaymentMethodDto)
                .toList();

        log.debug("getPaymentOptions user={} market={}/{} fallback={} methods={}",
                userId, market.resolvedCountryCode(), market.platform(),
                market.fallbackToGlobal(), methods.size());

        return new PaymentOptionsResponse(
                null,
                null,
                market.platform(),
                market.billingCountryCode(),
                market.resolvedCountryCode(),
                market.fallbackToGlobal(),
                methods
        );
    }

    public List<OfferDto> getOffers(UUID userId, String platform) {
        return getOffers(userId, platform, platform);
    }

    public List<OfferDto> getOffers(UUID userId, String platform, String clientPlatform) {
        BillingMarketResolver.MarketResult market = marketResolver.resolveMarket(userId, platform);
        int methodCount = billingRepo.countActivePaymentMethods(
                market.resolvedCountryCode(), market.platform());

        log.debug("getOffers user={} market={}/{} methodCount={}",
                userId, market.resolvedCountryCode(), market.platform(), methodCount);

        // Country-level payment mode gate — use the user's actual billing country,
        // not the resolved market country (which may have fallen back to GLOBAL).
        // CountrySettingsService.getSettings() already falls back to GLOBAL if no
        // row exists for the given country.
        CountrySettingsService.CountrySettings settings =
                countrySettingsService.getSettings(market.billingCountryCode());

        java.util.Set<String> unlimitedTypes = billingRepo.getUnlimitedEntitlementTypes(userId);

        List<BillingRepository.OfferRow> rows =
                billingRepo.findActiveOffers(market.platform(), market.resolvedCountryCode());

        String trustedCountry = "ET".equalsIgnoreCase(market.billingCountryCode()) ? "ET" : "GLOBAL";

        List<OfferDto> result = rows.stream()
                .filter(o -> {
                    // Filter by country payment mode settings
                    boolean isSubscription = o.subProductCode() != null;
                    if (isSubscription && !settings.subscriptionEnabled()) {
                        log.debug("Hiding subscription offer {} – subscriptions disabled for country={}",
                                o.id(), settings.countryCode());
                        return false;
                    }
                    if (!isSubscription && !settings.creditsEnabled()) {
                        log.debug("Hiding consumable offer {} – credits disabled for country={}",
                                o.id(), settings.countryCode());
                        return false;
                    }
                    // Filter out consumables the user already has unlimited allowance for
                    if (o.entitlementType() != null && unlimitedTypes.contains(o.entitlementType())) {
                        log.debug("Hiding consumable offer {} – user has unlimited {}",
                                o.id(), o.entitlementType());
                        return false;
                    }
                    return true;
                })
                .map(o -> toOfferDto(o, methodCount, userId, trustedCountry, clientPlatform))
                .toList();

        log.debug("getOffers returning {} offers (filtered {} by country settings + unlimited consumables)",
                result.size(), rows.size() - result.size());
        return result;
    }

    private OfferDto toOfferDto(BillingRepository.OfferRow row, int methodCount,
                                  UUID userId, String trustedCountry, String clientPlatform) {
        String productCode = row.subProductCode() != null ? row.subProductCode() : row.conProductCode();
        String productType = row.subProductCode() != null ? "SUBSCRIPTION" : "CONSUMABLE";
        String displayPrice = formatPrice(row.priceMinorUnits(), row.currency());

        int effectivePrice = row.priceMinorUnits();
        String effectiveDisplayPrice = displayPrice;
        PromotionDto promotionDto = null;
        List<ClaimablePromotionDto> claimableDtos = List.of();

        UUID productId = row.subscriptionProductId() != null
                ? row.subscriptionProductId() : row.consumableProductId();
        if (productId != null) {
            Optional<PromotionEligibilityService.AppliedPromotion> applied =
                    promotionEligibilityService.findBestPurchasePromotion(
                            userId, productId,
                            row.priceMinorUnits(), row.currency(), trustedCountry);
            if (applied.isPresent()) {
                var ap = applied.get();
                effectivePrice = (int) ap.discount().finalAmountMinor();
                effectiveDisplayPrice = formatPrice(effectivePrice, row.currency());
                promotionDto = toPromotionDto(ap, row.currency());
            }

            List<PromotionRepository.CampaignRow> claimable =
                    promotionEligibilityService.findClaimablePromotions(
                            userId, productId, trustedCountry);
            claimableDtos = claimable.stream().map(this::toClaimablePromotionDto).toList();
        }

        String externalProductId = resolveExternalProductId(row, clientPlatform);

        return new OfferDto(
                row.id(),
                productCode,
                productType,
                row.countryCode(),
                row.currency(),
                row.priceMinorUnits(),
                displayPrice,
                effectivePrice,
                effectiveDisplayPrice,
                row.billingIntervalCount(),
                row.billingIntervalUnit(),
                row.includedCredits(),
                row.autoRenew(),
                externalProductId,
                row.revenuecatOfferingId(),
                row.revenuecatPackageId(),
                methodCount > 0,
                methodCount,
                promotionDto,
                claimableDtos
        );
    }

    private static String resolveExternalProductId(BillingRepository.OfferRow row, String clientPlatform) {
        if (clientPlatform != null) {
            String upper = clientPlatform.toUpperCase();
            if ("IOS".equals(upper) && !isBlank(row.appleProductId())) return row.appleProductId();
            if ("ANDROID".equals(upper) && !isBlank(row.googleProductId())) return row.googleProductId();
        }
        return isBlank(row.externalProductId()) ? null : row.externalProductId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private PromotionDto toPromotionDto(PromotionEligibilityService.AppliedPromotion ap,
                                         String currency) {
        var c = ap.campaign();
        var d = ap.discount();
        return new PromotionDto(
                c.id(), c.campaignKey(), c.name(), c.description(),
                c.discountType(), c.discountValue() != null ? c.discountValue() : 0,
                c.discountCurrency(),
                d.originalAmountMinor(), d.discountAmountMinor(), d.finalAmountMinor(),
                formatPrice((int) d.finalAmountMinor(), currency),
                c.endsAt() != null ? ISO_FMT.format(c.endsAt()) : null
        );
    }

    private ClaimablePromotionDto toClaimablePromotionDto(PromotionRepository.CampaignRow c) {
        return new ClaimablePromotionDto(
                c.id(), c.campaignKey(), c.name(), c.description(),
                c.durationDays(),
                c.endsAt() != null ? ISO_FMT.format(c.endsAt()) : null
        );
    }

    private PaymentMethodDto toPaymentMethodDto(BillingRepository.PaymentMethodRow row) {
        return new PaymentMethodDto(
                row.id(),
                row.methodCode(),
                row.displayName(),
                row.paymentChannel(),
                row.paymentMethod(),
                row.paymentInstructions(),
                row.displayOrder(),
                row.verificationParams(),
                row.logoUrl()
        );
    }

    private static final Map<String, Integer> CHANNEL_DISPLAY_ORDER = new LinkedHashMap<>();
    static {
        CHANNEL_DISPLAY_ORDER.put("ONLINE_PAYMENT", 1);
        CHANNEL_DISPLAY_ORDER.put("MANUAL_TRANSFER", 2);
    }

    private static final Map<String, String> CHANNEL_DISPLAY_NAMES = new LinkedHashMap<>();
    static {
        CHANNEL_DISPLAY_NAMES.put("ONLINE_PAYMENT", "Pay Online");
        CHANNEL_DISPLAY_NAMES.put("MANUAL_TRANSFER", "Bank / Mobile Transfer");
    }

    public PaymentChannelsResponse getPaymentChannels(UUID userId, String platform) {
        BillingMarketResolver.MarketResult market = marketResolver.resolveMethodsMarket(userId, platform);
        List<String> channels = billingRepo.findDistinctPaymentChannels(
                market.resolvedCountryCode(), market.platform());

        List<PaymentChannelDto> result = new ArrayList<>();
        for (String c : channels) {
            int methodCount = billingRepo.countActivePaymentMethodsByChannel(
                    market.resolvedCountryCode(), market.platform(), c);
            String activeOnlineMethodCode = null;
            if ("ONLINE_PAYMENT".equals(c)) {
                activeOnlineMethodCode = billingRepo
                        .findActiveOnlinePaymentMethod(market.resolvedCountryCode(), market.platform())
                        .map(BillingRepository.PaymentMethodRow::methodCode)
                        .orElse(null);
            }
            result.add(new PaymentChannelDto(
                    c,
                    CHANNEL_DISPLAY_NAMES.getOrDefault(c, c),
                    activeOnlineMethodCode,
                    CHANNEL_DISPLAY_ORDER.getOrDefault(c, 99),
                    methodCount
            ));
        }
        result.sort(java.util.Comparator.comparingInt(PaymentChannelDto::displayOrder));

        log.debug("getPaymentChannels user={} market={}/{} channels={}",
                userId, market.resolvedCountryCode(), market.platform(), result.size());

        return new PaymentChannelsResponse(
                market.platform(),
                market.billingCountryCode(),
                market.resolvedCountryCode(),
                market.fallbackToGlobal(),
                result
        );
    }

    public PaymentOptionsResponse getPaymentMethodsByChannel(UUID userId, String platform, String channel) {
        BillingMarketResolver.MarketResult market = marketResolver.resolveMethodsMarket(userId, platform);
        List<PaymentMethodDto> methods = billingRepo
                .findActivePaymentMethodsByChannel(market.resolvedCountryCode(), market.platform(), channel)
                .stream()
                .map(this::toPaymentMethodDto)
                .toList();

        String activeOnlineMethodCode = null;
        if ("ONLINE_PAYMENT".equals(channel)) {
            activeOnlineMethodCode = methods.isEmpty() ? null : methods.get(0).methodCode();
        }

        return new PaymentOptionsResponse(
                channel,
                activeOnlineMethodCode,
                market.platform(),
                market.billingCountryCode(),
                market.resolvedCountryCode(),
                market.fallbackToGlobal(),
                methods
        );
    }

    private String formatPrice(int minorUnits, String currency) {
        double amount = minorUnits / 100.0;
        return String.format("%s %.2f", currency, amount);
    }
}
