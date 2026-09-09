package com.qaliye.backend.billing.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qaliye.backend.billing.BillingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ArifPayGatewayClientTest {

    @Mock BillingProperties billingProps;
    @Mock BillingProperties.ArifPay arifPayCfg;
    @Mock RestClient restClient;
    @Mock RestClient.RequestBodyUriSpec postSpec;
    @Mock RestClient.RequestBodySpec postBodySpec;
    @Mock RestClient.ResponseSpec postResponseSpec;
    @Mock RestClient.RequestHeadersUriSpec<?> getSpec;
    @Mock RestClient.RequestHeadersSpec<?> getHeadersSpec;
    @Mock RestClient.ResponseSpec getResponseSpec;

    ArifPayGatewayClient client;
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        client = new ArifPayGatewayClient(billingProps, restClient, objectMapper);
        lenient().when(billingProps.getArifPay()).thenReturn(arifPayCfg);
        lenient().when(arifPayCfg.getSecretKey()).thenReturn("test-api-key");
        lenient().when(arifPayCfg.getBaseUrl()).thenReturn("https://gateway.arifpay.net");
        lenient().when(arifPayCfg.getWebhookUrl()).thenReturn("https://example.com/webhook");
        lenient().when(arifPayCfg.getReturnUrl()).thenReturn("qaliyedating://payments/callback");
        lenient().when(arifPayCfg.getCancelUrl()).thenReturn("qaliyedating://payments/callback");
        lenient().when(arifPayCfg.getErrorUrl()).thenReturn("qaliyedating://payments/callback");
        lenient().when(arifPayCfg.getEnvironment()).thenReturn("PRODUCTION");
        lenient().when(arifPayCfg.getEmail()).thenReturn("support@qaliye.com");
    }

    @Test
    void getMethodCode_returnsArifpay() {
        assertThat(client.getMethodCode()).isEqualTo("arifpay");
    }

    @Test
    void isConfigured_withKey_returnsTrue() {
        when(arifPayCfg.getSecretKey()).thenReturn("some-key");
        assertThat(client.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_emptyKey_returnsFalse() {
        when(arifPayCfg.getSecretKey()).thenReturn("");
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    void isConfigured_nullKey_returnsFalse() {
        when(arifPayCfg.getSecretKey()).thenReturn(null);
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createCheckout_production_callsCorrectEndpoint() throws Exception {
        String responseJson = """
                {"data":{"sessionId":"sess-123","paymentUrl":"https://checkout.arifpay.net/sess-123"}}
                """;

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(contains("/api/checkout/session"))).thenReturn(postBodySpec);
        when(postBodySpec.header(eq("x-arifpay-key"), any())).thenReturn(postBodySpec);
        when(postBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(postBodySpec);
        when(postBodySpec.body(any(Object.class))).thenReturn(postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.body(String.class)).thenReturn(responseJson);

        UUID orderId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        LocalOnlinePaymentGateway.CheckoutResult result =
                client.createCheckout("QAL-ABC12345", 49900, "ETB", "user-1", null, "251911234567", orderId);

        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.arifpay.net/sess-123");
        assertThat(result.txRef()).isEqualTo("sess-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createCheckout_sandbox_callsSandboxPath() throws Exception {
        when(arifPayCfg.getEnvironment()).thenReturn("SANDBOX");
        when(arifPayCfg.getBaseUrl()).thenReturn("https://gateway.arifpay.org");

        String responseJson = """
                {"data":{"sessionId":"sandbox-sess","paymentUrl":"https://payment.arifpay.org/sandbox-sess"}}
                """;

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(contains("/api/sandbox/c2b/session"))).thenReturn(postBodySpec);
        when(postBodySpec.header(eq("x-arifpay-key"), any())).thenReturn(postBodySpec);
        when(postBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(postBodySpec);
        when(postBodySpec.body(any(Object.class))).thenReturn(postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.body(String.class)).thenReturn(responseJson);

        UUID orderId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        LocalOnlinePaymentGateway.CheckoutResult result =
                client.createCheckout("QAL-SBX12345", 9900, "ETB", "user-2", null, "251911234567", orderId);

        assertThat(result.txRef()).isEqualTo("sandbox-sess");
        assertThat(result.checkoutUrl()).isEqualTo("https://payment.arifpay.org/sandbox-sess");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createCheckout_noPaymentUrl_usesFallback() throws Exception {
        String responseJson = """
                {"data":{"sessionId":"sess-456"}}
                """;

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(any(), any())).thenReturn(postBodySpec);
        when(postBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(postBodySpec);
        when(postBodySpec.body(any(Object.class))).thenReturn(postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.body(String.class)).thenReturn(responseJson);

        LocalOnlinePaymentGateway.CheckoutResult result =
                client.createCheckout("QAL-FALLBACK", 9900, "ETB", "user-3", null, null,
                        UUID.fromString("33333333-3333-3333-3333-333333333333"));

        assertThat(result.txRef()).isEqualTo("sess-456");
        assertThat(result.checkoutUrl()).contains("sess-456");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createCheckout_noSessionId_throwsArifPayApiException() throws Exception {
        String responseJson = """
                {"data":{}}
                """;

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(any(), any())).thenReturn(postBodySpec);
        when(postBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(postBodySpec);
        when(postBodySpec.body(any(Object.class))).thenReturn(postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.body(String.class)).thenReturn(responseJson);

        assertThatThrownBy(() ->
                client.createCheckout("QAL-NOID", 9900, "ETB", "user-4", null, null,
                        UUID.fromString("44444444-4444-4444-4444-444444444444")))
                .isInstanceOf(ArifPayGatewayClient.ArifPayApiException.class)
                .hasMessageContaining("arifpay_no_session_id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createCheckout_ignoresRequestReturnUrl_usesConfigUrl() throws Exception {
        String customReturnUrl = "https://merchant.example/success";
        String responseJson = """
                {"data":{"sessionId":"sess-789","paymentUrl":"https://checkout.arifpay.net/sess-789"}}
                """;

        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(postBodySpec);
        when(postBodySpec.header(any(), any())).thenReturn(postBodySpec);
        when(postBodySpec.contentType(MediaType.APPLICATION_JSON)).thenReturn(postBodySpec);
        when(postBodySpec.body(any(Object.class))).thenReturn(postBodySpec);
        when(postBodySpec.retrieve()).thenReturn(postResponseSpec);
        when(postResponseSpec.body(String.class)).thenReturn(responseJson);

        UUID orderId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        LocalOnlinePaymentGateway.CheckoutResult result =
                client.createCheckout("QAL-RETURL", 9900, "ETB", "user-5", customReturnUrl, "251911234567", orderId);

        assertThat(result.checkoutUrl()).isEqualTo("https://checkout.arifpay.net/sess-789");
        assertThat(result.txRef()).isEqualTo("sess-789");
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void verifySession_success_returnsSuccessResult() throws Exception {
        String responseJson = """
                {"data":{"status":"SUCCESS","nonce":"QAL-ABC","amount":"499.00","currency":"ETB","transactionId":"TXN-001"}}
                """;

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getSpec);
        doReturn(getHeadersSpec).when(getSpec).uri(contains("/api/verify/transaction?uuid=sess-123"));
        doReturn(getHeadersSpec).when(getHeadersSpec).header(eq("x-arifpay-key"), any());
        when(getHeadersSpec.retrieve()).thenReturn(getResponseSpec);
        when(getResponseSpec.body(String.class)).thenReturn(responseJson);

        ArifPayGatewayClient.VerifyResult result = client.verifySession("sess-123");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.nonce()).isEqualTo("QAL-ABC");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isFailed()).isFalse();
        assertThat(result.amountMinorUnits()).isEqualTo(49900);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void verifySession_failed_returnsFailedResult() throws Exception {
        String responseJson = """
                {"data":{"status":"FAILED","nonce":"QAL-DEF","currency":"ETB"}}
                """;

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getSpec);
        doReturn(getHeadersSpec).when(getSpec).uri(anyString());
        doReturn(getHeadersSpec).when(getHeadersSpec).header(any(), any());
        when(getHeadersSpec.retrieve()).thenReturn(getResponseSpec);
        when(getResponseSpec.body(String.class)).thenReturn(responseJson);

        ArifPayGatewayClient.VerifyResult result = client.verifySession("sess-fail");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailed()).isTrue();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void verifySession_networkError_returnsErrorResult() {
        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getSpec);
        doReturn(getHeadersSpec).when(getSpec).uri(anyString());
        doReturn(getHeadersSpec).when(getHeadersSpec).header(any(), any());
        when(getHeadersSpec.retrieve()).thenReturn(getResponseSpec);
        when(getResponseSpec.body(String.class)).thenThrow(new RuntimeException("connection refused"));

        ArifPayGatewayClient.VerifyResult result = client.verifySession("sess-err");

        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isFailed()).isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void verifySession_sandbox_usesSandboxVerifyPath() throws Exception {
        when(arifPayCfg.getEnvironment()).thenReturn("SANDBOX");
        String responseJson = """
                {"data":{"status":"SUCCESS","nonce":"QAL-SBX","amount":"50.00","currency":"ETB"}}
                """;

        when(restClient.get()).thenReturn((RestClient.RequestHeadersUriSpec) getSpec);
        doReturn(getHeadersSpec).when(getSpec).uri(contains("/api/sandbox/verify/transaction?uuid=sbx-sess"));
        doReturn(getHeadersSpec).when(getHeadersSpec).header(any(), any());
        when(getHeadersSpec.retrieve()).thenReturn(getResponseSpec);
        when(getResponseSpec.body(String.class)).thenReturn(responseJson);

        ArifPayGatewayClient.VerifyResult result = client.verifySession("sbx-sess");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void verifyResult_amountMinorUnits_parsesCorrectly() {
        ArifPayGatewayClient.VerifyResult result =
                new ArifPayGatewayClient.VerifyResult("SUCCESS", "QAL-X", "99.00", "ETB", "TXN-1");
        assertThat(result.amountMinorUnits()).isEqualTo(9900);
    }

    @Test
    void verifyResult_amountMinorUnits_nullAmount_returnsNull() {
        ArifPayGatewayClient.VerifyResult result =
                new ArifPayGatewayClient.VerifyResult("SUCCESS", "QAL-X", null, "ETB", "TXN-1");
        assertThat(result.amountMinorUnits()).isNull();
    }

    @Test
    void verifyResult_cancelledStatus_isFailed() {
        ArifPayGatewayClient.VerifyResult result =
                new ArifPayGatewayClient.VerifyResult("CANCELLED", null, null, null, null);
        assertThat(result.isFailed()).isTrue();
    }

    @Test
    void verifyResult_expiredStatus_isFailed() {
        ArifPayGatewayClient.VerifyResult result =
                new ArifPayGatewayClient.VerifyResult("EXPIRED", null, null, null, null);
        assertThat(result.isFailed()).isTrue();
    }
}
