package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.dto.OfferDto;
import com.qaliye.backend.billing.dto.PaymentChannelDto;
import com.qaliye.backend.billing.dto.PaymentChannelsResponse;
import com.qaliye.backend.billing.dto.PaymentMethodDto;
import com.qaliye.backend.billing.dto.PaymentOptionsResponse;
import com.qaliye.backend.billing.repository.BillingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final BillingRepository billingRepo;
    private final BillingMarketResolver marketResolver;

    public OfferService(BillingRepository billingRepo, BillingMarketResolver marketResolver) {
        this.billingRepo = billingRepo;
        this.marketResolver = marketResolver;
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
        BillingMarketResolver.MarketResult market = marketResolver.resolveMarket(userId, platform);
        int methodCount = billingRepo.countActivePaymentMethods(
                market.resolvedCountryCode(), market.platform());

        log.debug("getOffers user={} market={}/{} methodCount={}",
                userId, market.resolvedCountryCode(), market.platform(), methodCount);

        java.util.Set<String> unlimitedTypes = billingRepo.getUnlimitedEntitlementTypes(userId);

        List<BillingRepository.OfferRow> rows =
                billingRepo.findActiveOffers(market.platform(), market.resolvedCountryCode());

        List<OfferDto> result = rows.stream()
                .filter(o -> {
                    if (o.entitlementType() != null && unlimitedTypes.contains(o.entitlementType())) {
                        log.debug("Hiding consumable offer {} – user has unlimited {}",
                                o.id(), o.entitlementType());
                        return false;
                    }
                    return true;
                })
                .map(o -> toOfferDto(o, methodCount))
                .toList();

        log.debug("getOffers returning {} offers (filtered {} unlimited consumables)",
                result.size(), rows.size() - result.size());
        return result;
    }

    private OfferDto toOfferDto(BillingRepository.OfferRow row, int methodCount) {
        String productCode = row.subProductCode() != null ? row.subProductCode() : row.conProductCode();
        String productType = row.subProductCode() != null ? "SUBSCRIPTION" : "CONSUMABLE";
        String displayPrice = formatPrice(row.priceMinorUnits(), row.currency());

        return new OfferDto(
                row.id(),
                productCode,
                productType,
                row.countryCode(),
                row.currency(),
                row.priceMinorUnits(),
                displayPrice,
                row.billingIntervalCount(),
                row.billingIntervalUnit(),
                row.autoRenew(),
                row.externalProductId(),
                row.revenuecatOfferingId(),
                row.revenuecatPackageId(),
                methodCount > 0,
                methodCount
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
                row.verificationParams()
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
