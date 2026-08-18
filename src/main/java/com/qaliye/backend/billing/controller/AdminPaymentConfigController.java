package com.qaliye.backend.billing.controller;

import com.qaliye.backend.billing.dto.admin.*;
import com.qaliye.backend.billing.service.AdminPaymentConfigService;
import com.qaliye.backend.common.CallerUtils;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/payment-config")
public class AdminPaymentConfigController {

    private final AdminPaymentConfigService configService;

    public AdminPaymentConfigController(AdminPaymentConfigService configService) {
        this.configService = configService;
    }

    // =========================================================================
    // subscription_plans
    // =========================================================================

    @GetMapping("/subscription-plans")
    public ResponseEntity<List<?>> listPlans() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listPlans(adminId));
    }

    @GetMapping("/subscription-plans/{id}")
    public ResponseEntity<?> getPlan(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getPlan(adminId, id));
    }

    @PostMapping("/subscription-plans")
    public ResponseEntity<?> createPlan(@Valid @RequestBody CreateSubscriptionPlanRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createPlan(adminId, request));
    }

    @PutMapping("/subscription-plans/{id}")
    public ResponseEntity<?> updatePlan(@PathVariable UUID id, @RequestBody UpdateSubscriptionPlanRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updatePlan(adminId, id, request));
    }

    @DeleteMapping("/subscription-plans/{id}")
    public ResponseEntity<Void> deactivatePlan(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deactivatePlan(adminId, id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // subscription_products
    // =========================================================================

    @GetMapping("/subscription-products")
    public ResponseEntity<List<?>> listSubscriptionProducts() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listSubscriptionProducts(adminId));
    }

    @GetMapping("/subscription-products/{id}")
    public ResponseEntity<?> getSubscriptionProduct(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getSubscriptionProduct(adminId, id));
    }

    @PostMapping("/subscription-products")
    public ResponseEntity<?> createSubscriptionProduct(@Valid @RequestBody CreateSubscriptionProductRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createSubscriptionProduct(adminId, request));
    }

    @PutMapping("/subscription-products/{id}")
    public ResponseEntity<?> updateSubscriptionProduct(@PathVariable UUID id, @RequestBody UpdateSubscriptionProductRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updateSubscriptionProduct(adminId, id, request));
    }

    @DeleteMapping("/subscription-products/{id}")
    public ResponseEntity<Void> deactivateSubscriptionProduct(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deactivateSubscriptionProduct(adminId, id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // consumable_products
    // =========================================================================

    @GetMapping("/consumable-products")
    public ResponseEntity<List<?>> listConsumableProducts() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listConsumableProducts(adminId));
    }

    @GetMapping("/consumable-products/{id}")
    public ResponseEntity<?> getConsumableProduct(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getConsumableProduct(adminId, id));
    }

    @PostMapping("/consumable-products")
    public ResponseEntity<?> createConsumableProduct(@Valid @RequestBody CreateConsumableProductRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createConsumableProduct(adminId, request));
    }

    @PutMapping("/consumable-products/{id}")
    public ResponseEntity<?> updateConsumableProduct(@PathVariable UUID id, @RequestBody UpdateConsumableProductRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updateConsumableProduct(adminId, id, request));
    }

    @DeleteMapping("/consumable-products/{id}")
    public ResponseEntity<Void> deactivateConsumableProduct(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deactivateConsumableProduct(adminId, id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // payment_offers
    // =========================================================================

    @GetMapping("/payment-offers")
    public ResponseEntity<List<?>> listPaymentOffers() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listPaymentOffers(adminId));
    }

    @GetMapping("/payment-offers/{id}")
    public ResponseEntity<?> getPaymentOffer(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getPaymentOffer(adminId, id));
    }

    @PostMapping("/payment-offers")
    public ResponseEntity<?> createPaymentOffer(@Valid @RequestBody CreatePaymentOfferRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createPaymentOffer(adminId, request));
    }

    @PutMapping("/payment-offers/{id}")
    public ResponseEntity<?> updatePaymentOffer(@PathVariable UUID id, @RequestBody UpdatePaymentOfferRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updatePaymentOffer(adminId, id, request));
    }

    @DeleteMapping("/payment-offers/{id}")
    public ResponseEntity<Void> deactivatePaymentOffer(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deactivatePaymentOffer(adminId, id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // payment_methods
    // =========================================================================

    @GetMapping("/payment-methods")
    public ResponseEntity<List<?>> listPaymentMethods() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listPaymentMethods(adminId));
    }

    @GetMapping("/payment-methods/{id}")
    public ResponseEntity<?> getPaymentMethod(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getPaymentMethod(adminId, id));
    }

    @PostMapping("/payment-methods")
    public ResponseEntity<?> createPaymentMethod(@Valid @RequestBody CreatePaymentMethodRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createPaymentMethod(adminId, request));
    }

    @PutMapping("/payment-methods/{id}")
    public ResponseEntity<?> updatePaymentMethod(@PathVariable UUID id, @RequestBody UpdatePaymentMethodRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updatePaymentMethod(adminId, id, request));
    }

    @DeleteMapping("/payment-methods/{id}")
    public ResponseEntity<Void> deactivatePaymentMethod(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deactivatePaymentMethod(adminId, id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // subscription_plan_limit_and_cost
    // =========================================================================

    @GetMapping("/plan-limit-costs")
    public ResponseEntity<List<?>> listPlanLimitCosts() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listPlanLimitCosts(adminId));
    }

    @GetMapping("/plan-limit-costs/{id}")
    public ResponseEntity<?> getPlanLimitCost(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getPlanLimitCost(adminId, id));
    }

    @PostMapping("/plan-limit-costs")
    public ResponseEntity<?> createPlanLimitCost(@Valid @RequestBody CreatePlanLimitCostRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createPlanLimitCost(adminId, request));
    }

    @PutMapping("/plan-limit-costs/{id}")
    public ResponseEntity<?> updatePlanLimitCost(@PathVariable UUID id, @RequestBody UpdatePlanLimitCostRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updatePlanLimitCost(adminId, id, request));
    }

    @DeleteMapping("/plan-limit-costs/{id}")
    public ResponseEntity<Void> deletePlanLimitCost(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deletePlanLimitCost(adminId, id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // feature_actions
    // =========================================================================

    @GetMapping("/feature-actions")
    public ResponseEntity<List<?>> listFeatureActions() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listFeatureActions(adminId));
    }

    @GetMapping("/feature-actions/{id}")
    public ResponseEntity<?> getFeatureAction(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getFeatureAction(adminId, id));
    }

    @PostMapping("/feature-actions")
    public ResponseEntity<?> createFeatureAction(@Valid @RequestBody CreateFeatureActionRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createFeatureAction(adminId, request));
    }

    @PutMapping("/feature-actions/{id}")
    public ResponseEntity<?> updateFeatureAction(@PathVariable UUID id, @RequestBody UpdateFeatureActionRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updateFeatureAction(adminId, id, request));
    }

    @DeleteMapping("/feature-actions/{id}")
    public ResponseEntity<Void> deleteFeatureAction(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deleteFeatureAction(adminId, id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    // country_settings
    // =========================================================================

    @GetMapping("/country-settings")
    public ResponseEntity<List<?>> listCountrySettings() {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.listCountrySettings(adminId));
    }

    @GetMapping("/country-settings/{id}")
    public ResponseEntity<?> getCountrySetting(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.getCountrySetting(adminId, id));
    }

    @PostMapping("/country-settings")
    public ResponseEntity<?> createCountrySetting(@Valid @RequestBody CreateCountrySettingRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.status(HttpStatus.CREATED).body(configService.createCountrySetting(adminId, request));
    }

    @PutMapping("/country-settings/{id}")
    public ResponseEntity<?> updateCountrySetting(@PathVariable UUID id, @RequestBody UpdateCountrySettingRequest request) {
        UUID adminId = CallerUtils.callerId();
        return ResponseEntity.ok(configService.updateCountrySetting(adminId, id, request));
    }

    @DeleteMapping("/country-settings/{id}")
    public ResponseEntity<Void> deleteCountrySetting(@PathVariable UUID id) {
        UUID adminId = CallerUtils.callerId();
        configService.deleteCountrySetting(adminId, id);
        return ResponseEntity.noContent().build();
    }
}
