package com.qaliye.backend.billing.dto;

import java.util.List;

public record OrderListResponse(
        List<OrderSummaryDto> orders,
        int page,
        int pageSize,
        long total,
        int totalPages
) {}
