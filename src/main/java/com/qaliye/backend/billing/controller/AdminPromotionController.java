package com.qaliye.backend.billing.controller;

import com.qaliye.backend.billing.dto.CampaignDto;
import com.qaliye.backend.billing.dto.CreateCampaignRequest;
import com.qaliye.backend.billing.dto.RedemptionDto;
import com.qaliye.backend.billing.dto.UpdateCampaignRequest;
import com.qaliye.backend.billing.service.AdminPromotionService;
import com.qaliye.backend.common.CallerUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/billing/campaigns")
public class AdminPromotionController {

    private final AdminPromotionService adminPromotionService;

    public AdminPromotionController(AdminPromotionService adminPromotionService) {
        this.adminPromotionService = adminPromotionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignDto create(@RequestBody CreateCampaignRequest request) {
        return adminPromotionService.createCampaign(request, CallerUtils.callerId());
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminPromotionService.listCampaigns(status, page, pageSize);
    }

    @GetMapping("/{id}")
    public CampaignDto get(@PathVariable UUID id) {
        return adminPromotionService.getCampaign(id);
    }

    @PutMapping("/{id}")
    public CampaignDto update(@PathVariable UUID id, @RequestBody UpdateCampaignRequest request) {
        return adminPromotionService.updateCampaign(id, request);
    }

    @PostMapping("/{id}/activate")
    public CampaignDto activate(@PathVariable UUID id) {
        return adminPromotionService.activateCampaign(id);
    }

    @PostMapping("/{id}/pause")
    public CampaignDto pause(@PathVariable UUID id) {
        return adminPromotionService.pauseCampaign(id);
    }

    @PostMapping("/{id}/expire")
    public CampaignDto expire(@PathVariable UUID id) {
        return adminPromotionService.expireCampaign(id);
    }

    @GetMapping("/{id}/redemptions")
    public List<RedemptionDto> listRedemptions(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return adminPromotionService.listRedemptions(id, page, pageSize);
    }
}
