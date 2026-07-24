package com.qaliye.backend.admin;

import com.qaliye.backend.billing.repository.BillingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminRefundServiceTest {

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private BillingRepository billingRepo;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    @InjectMocks
    private AdminRefundService service;

    private UUID adminId;
    private UUID orderId;
    private UUID userId;
    private UUID transactionId;
    private BillingRepository.OrderRow order;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        userId = UUID.randomUUID();
        transactionId = UUID.randomUUID();
        order = new BillingRepository.OrderRow(
                orderId, userId, UUID.randomUUID(), UUID.randomUUID(),
                "QAL-ABC12345", "VERIFIED", null,
                1000, "ETB",
                "ONLINE", "Chapa", "chapa", "Chapa",
                null, null,
                Instant.now().plusSeconds(3600), Instant.now().minusSeconds(3600), Instant.now(),
                null, null, null, 1
        );
    }

    @Test
    void refundOrder_succeeds_forVerifiedOrder() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", adminId))))
                .thenReturn(List.of(Map.of("role", "ADMIN")));
        when(billingRepo.findOrderById(orderId))
                .thenReturn(Optional.of(order));
        when(jdbc.queryForList(anyString(), eq(Map.of("orderId", orderId))))
                .thenReturn(List.of(buildTxRow()));
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(1);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.<UUID>of());
        when(billingRepo.insertTransaction(any(), any(), any(), any(), any(),
                anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        Map<String, Object> result = service.refundOrder(adminId, orderId, "customer_request");

        assertEquals("CANCELLED", result.get("status"));
        assertEquals(orderId, result.get("orderId"));
        assertEquals(1000, result.get("refundAmount"));
    }

    @Test
    void refundOrder_throwsNotFound_whenOrderMissing() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", adminId))))
                .thenReturn(List.of(Map.of("role", "ADMIN")));
        when(billingRepo.findOrderById(orderId))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(adminId, orderId, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void refundOrder_throwsBadRequest_whenOrderNotVerified() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", adminId))))
                .thenReturn(List.of(Map.of("role", "ADMIN")));
        when(billingRepo.findOrderById(orderId))
                .thenReturn(Optional.of(new BillingRepository.OrderRow(
                        orderId, userId, UUID.randomUUID(), UUID.randomUUID(),
                        "QAL-ABC12345", "REJECTED", null,
                        1000, "ETB", "ONLINE", "Chapa", "chapa", "Chapa",
                        null, null, Instant.now(), Instant.now(), Instant.now(),
                        null, null, null, 1
                )));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(adminId, orderId, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void refundOrder_throwsNotFound_whenNoCompletedTransaction() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", adminId))))
                .thenReturn(List.of(Map.of("role", "ADMIN")));
        when(billingRepo.findOrderById(orderId))
                .thenReturn(Optional.of(order));
        when(jdbc.queryForList(anyString(), eq(Map.of("orderId", orderId))))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(adminId, orderId, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void refundOrder_throwsForbidden_forNonAdmin() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", adminId))))
                .thenReturn(List.of(Map.of("role", "USER")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(adminId, orderId, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void refundOrder_throwsForbidden_whenAdminNotFound() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", adminId))))
                .thenReturn(List.of());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.refundOrder(adminId, orderId, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void refundOrder_worksWithNullReason() {
        when(jdbc.queryForList(anyString(), eq(Map.of("userId", adminId))))
                .thenReturn(List.of(Map.of("role", "ADMIN")));
        when(billingRepo.findOrderById(orderId))
                .thenReturn(Optional.of(order));
        when(jdbc.queryForList(anyString(), eq(Map.of("orderId", orderId))))
                .thenReturn(List.of(buildTxRow()));
        when(jdbc.update(anyString(), any(Map.class))).thenReturn(1);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.<UUID>of());
        when(billingRepo.insertTransaction(any(), any(), any(), any(), any(),
                anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyString(), any(), anyString(), anyString()))
                .thenReturn(UUID.randomUUID());
        when(cacheManager.getCache("subscriptionFeatures")).thenReturn(cache);

        Map<String, Object> result = service.refundOrder(adminId, orderId, null);

        assertEquals("CANCELLED", result.get("status"));
    }

    private Map<String, Object> buildTxRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", transactionId);
        row.put("payment_purpose", "SUBSCRIPTION");
        row.put("amount_minor_units", 1000);
        row.put("currency", "ETB");
        row.put("provider", "CHAPA");
        row.put("country_code", "ET");
        return row;
    }
}
