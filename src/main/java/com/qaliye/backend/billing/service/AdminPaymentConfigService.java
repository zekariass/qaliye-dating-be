package com.qaliye.backend.billing.service;

import com.qaliye.backend.billing.dto.admin.*;
import com.qaliye.backend.billing.repository.AdminPaymentConfigRepository;
import com.qaliye.backend.billing.repository.AdminPaymentConfigRepository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class AdminPaymentConfigService {

    private final AdminPaymentConfigRepository repo;

    public AdminPaymentConfigService(AdminPaymentConfigRepository repo) {
        this.repo = repo;
    }

    private void enforceAdmin(UUID adminId) {
        if (!repo.isAdmin(adminId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin_required");
        }
    }

    // =========================================================================
    // subscription_plans
    // =========================================================================

    public List<SubscriptionPlanRow> listPlans(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listPlans();
    }

    public SubscriptionPlanRow getPlan(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findPlanById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "plan_not_found"));
    }

    public SubscriptionPlanRow createPlan(UUID adminId, CreateSubscriptionPlanRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createPlan(req.name(), req.planCode(), req.countryCode(), req.planKind(),
                req.priceMinorUnits(), req.currency(), req.billingInterval(), req.features(), req.isActive());
        return repo.findPlanById(id).orElseThrow();
    }

    public SubscriptionPlanRow updatePlan(UUID adminId, UUID id, UpdateSubscriptionPlanRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updatePlan(id, req.name(), req.planCode(), req.countryCode(), req.planKind(),
                req.priceMinorUnits(), req.currency(), req.billingInterval(), req.features(), req.isActive());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "plan_not_found");
        return repo.findPlanById(id).orElseThrow();
    }

    public void deactivatePlan(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deactivatePlan(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "plan_not_found");
    }

    // =========================================================================
    // subscription_products
    // =========================================================================

    public List<SubscriptionProductRow> listSubscriptionProducts(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listSubscriptionProducts();
    }

    public SubscriptionProductRow getSubscriptionProduct(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findSubscriptionProductById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription_product_not_found"));
    }

    public SubscriptionProductRow createSubscriptionProduct(UUID adminId, CreateSubscriptionProductRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createSubscriptionProduct(req.planId(), req.productCode(), req.billingIntervalUnit(),
                req.billingIntervalCount(), req.autoRenewSupported(), req.includedCredits(), req.isActive());
        return repo.findSubscriptionProductById(id).orElseThrow();
    }

    public SubscriptionProductRow updateSubscriptionProduct(UUID adminId, UUID id, UpdateSubscriptionProductRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updateSubscriptionProduct(id, req.planId(), req.productCode(), req.billingIntervalUnit(),
                req.billingIntervalCount(), req.autoRenewSupported(), req.includedCredits(), req.isActive());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription_product_not_found");
        return repo.findSubscriptionProductById(id).orElseThrow();
    }

    public void deactivateSubscriptionProduct(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deactivateSubscriptionProduct(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "subscription_product_not_found");
    }

    // =========================================================================
    // consumable_products
    // =========================================================================

    public List<ConsumableProductRow> listConsumableProducts(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listConsumableProducts();
    }

    public ConsumableProductRow getConsumableProduct(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findConsumableProductById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "consumable_product_not_found"));
    }

    public ConsumableProductRow createConsumableProduct(UUID adminId, CreateConsumableProductRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createConsumableProduct(req.productCode(), req.name(), req.entitlementType(),
                req.quantityGranted(), req.expiresAfterDays(), req.isActive());
        return repo.findConsumableProductById(id).orElseThrow();
    }

    public ConsumableProductRow updateConsumableProduct(UUID adminId, UUID id, UpdateConsumableProductRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updateConsumableProduct(id, req.productCode(), req.name(), req.entitlementType(),
                req.quantityGranted(), req.expiresAfterDays(), req.isActive());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "consumable_product_not_found");
        return repo.findConsumableProductById(id).orElseThrow();
    }

    public void deactivateConsumableProduct(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deactivateConsumableProduct(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "consumable_product_not_found");
    }

    // =========================================================================
    // payment_offers
    // =========================================================================

    public List<PaymentOfferRow> listPaymentOffers(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listPaymentOffers();
    }

    public PaymentOfferRow getPaymentOffer(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findPaymentOfferById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment_offer_not_found"));
    }

    public PaymentOfferRow createPaymentOffer(UUID adminId, CreatePaymentOfferRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createPaymentOffer(req.subscriptionProductId(), req.consumableProductId(),
                req.countryCode(), req.platform(), req.currency(), req.priceMinorUnits(),
                req.externalProductId(), req.revenuecatOfferingId(), req.revenuecatPackageId(),
                req.autoRenew(), req.isActive());
        return repo.findPaymentOfferById(id).orElseThrow();
    }

    public PaymentOfferRow updatePaymentOffer(UUID adminId, UUID id, UpdatePaymentOfferRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updatePaymentOffer(id, req.subscriptionProductId(), req.consumableProductId(),
                req.countryCode(), req.platform(), req.currency(), req.priceMinorUnits(),
                req.externalProductId(), req.revenuecatOfferingId(), req.revenuecatPackageId(),
                req.autoRenew(), req.isActive());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment_offer_not_found");
        return repo.findPaymentOfferById(id).orElseThrow();
    }

    public void deactivatePaymentOffer(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deactivatePaymentOffer(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment_offer_not_found");
    }

    // =========================================================================
    // payment_methods
    // =========================================================================

    public List<PaymentMethodRow> listPaymentMethods(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listPaymentMethods();
    }

    public PaymentMethodRow getPaymentMethod(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findPaymentMethodById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "payment_method_not_found"));
    }

    public PaymentMethodRow createPaymentMethod(UUID adminId, CreatePaymentMethodRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createPaymentMethod(req.countryCode(), req.platform(), req.methodCode(),
                req.displayName(), req.paymentChannel(), req.paymentMethod(), req.paymentInstructions(),
                req.isActive(), req.displayOrder(), req.metadata(), req.verificationParams(), req.logoUrl());
        return repo.findPaymentMethodById(id).orElseThrow();
    }

    public PaymentMethodRow updatePaymentMethod(UUID adminId, UUID id, UpdatePaymentMethodRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updatePaymentMethod(id, req.countryCode(), req.platform(), req.methodCode(),
                req.displayName(), req.paymentChannel(), req.paymentMethod(), req.paymentInstructions(),
                req.isActive(), req.displayOrder(), req.metadata(), req.verificationParams(), req.logoUrl());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment_method_not_found");
        return repo.findPaymentMethodById(id).orElseThrow();
    }

    public void deactivatePaymentMethod(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deactivatePaymentMethod(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "payment_method_not_found");
    }

    // =========================================================================
    // subscription_plan_limit_and_cost
    // =========================================================================

    public List<PlanLimitCostRow> listPlanLimitCosts(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listPlanLimitCosts();
    }

    public PlanLimitCostRow getPlanLimitCost(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findPlanLimitCostById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "plan_limit_cost_not_found"));
    }

    public PlanLimitCostRow createPlanLimitCost(UUID adminId, CreatePlanLimitCostRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createPlanLimitCost(req.subscriptionPlanId(), req.featureActionId(),
                req.memberCreditCost(), req.actualCreditCost(), req.limitValue(), req.periodType(),
                req.applyCreditAfterLimit());
        return repo.findPlanLimitCostById(id).orElseThrow();
    }

    public PlanLimitCostRow updatePlanLimitCost(UUID adminId, UUID id, UpdatePlanLimitCostRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updatePlanLimitCost(id, req.subscriptionPlanId(), req.featureActionId(),
                req.memberCreditCost(), req.actualCreditCost(), req.limitValue(), req.periodType(),
                req.applyCreditAfterLimit());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "plan_limit_cost_not_found");
        return repo.findPlanLimitCostById(id).orElseThrow();
    }

    public void deletePlanLimitCost(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deletePlanLimitCost(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "plan_limit_cost_not_found");
    }

    // =========================================================================
    // feature_actions
    // =========================================================================

    public List<FeatureActionRow> listFeatureActions(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listFeatureActions();
    }

    public FeatureActionRow getFeatureAction(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findFeatureActionById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "feature_action_not_found"));
    }

    public FeatureActionRow createFeatureAction(UUID adminId, CreateFeatureActionRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createFeatureAction(req.code(), req.name(), req.type());
        return repo.findFeatureActionById(id).orElseThrow();
    }

    public FeatureActionRow updateFeatureAction(UUID adminId, UUID id, UpdateFeatureActionRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updateFeatureAction(id, req.code(), req.name(), req.type());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "feature_action_not_found");
        return repo.findFeatureActionById(id).orElseThrow();
    }

    public void deleteFeatureAction(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deleteFeatureAction(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "feature_action_not_found");
    }

    // =========================================================================
    // country_settings
    // =========================================================================

    public List<CountrySettingsRow> listCountrySettings(UUID adminId) {
        enforceAdmin(adminId);
        return repo.listCountrySettings();
    }

    public CountrySettingsRow getCountrySetting(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        return repo.findCountrySettingById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "country_setting_not_found"));
    }

    public CountrySettingsRow createCountrySetting(UUID adminId, CreateCountrySettingRequest req) {
        enforceAdmin(adminId);
        UUID id = repo.createCountrySetting(req.countryCode(), req.subscriptionEnabled(),
                req.creditsEnabled(), req.identityVerificationRequired());
        return repo.findCountrySettingById(id).orElseThrow();
    }

    public CountrySettingsRow updateCountrySetting(UUID adminId, UUID id, UpdateCountrySettingRequest req) {
        enforceAdmin(adminId);
        int rows = repo.updateCountrySetting(id, req.countryCode(), req.subscriptionEnabled(),
                req.creditsEnabled(), req.identityVerificationRequired());
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "country_setting_not_found");
        return repo.findCountrySettingById(id).orElseThrow();
    }

    public void deleteCountrySetting(UUID adminId, UUID id) {
        enforceAdmin(adminId);
        int rows = repo.deleteCountrySetting(id);
        if (rows == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "country_setting_not_found");
    }
}
