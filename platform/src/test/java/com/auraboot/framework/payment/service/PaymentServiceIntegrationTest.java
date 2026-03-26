package com.auraboot.framework.payment.service;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.entitlement.entity.PluginPlan;
import com.auraboot.framework.entitlement.mapper.PluginPlanMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.payment.dto.CheckoutRequest;
import com.auraboot.framework.payment.dto.CheckoutResponse;
import com.auraboot.framework.payment.dto.PaymentOrderDTO;
import com.auraboot.framework.payment.entity.PaymentOrder;
import com.auraboot.framework.payment.mapper.PaymentOrderMapper;
import com.auraboot.framework.payment.mapper.PaymentTransactionMapper;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.checkout.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for PaymentService.
 * Uses real PostgreSQL + Redis; mocks StripeService (external HTTP API).
 */
@TestPropertySource(properties = "auraboot.payment.enabled=true")
class PaymentServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentOrderMapper orderMapper;

    @Autowired
    private PaymentTransactionMapper transactionMapper;

    @Autowired
    private PluginPlanMapper planMapper;

    @MockBean
    private StripeService stripeService;

    private final String testPluginId = "test-pay-" + System.currentTimeMillis();

    @BeforeEach
    void resetStripeMocks() {
        reset(stripeService);
    }

    // ========== Helper ==========

    private PluginPlan seedPaidPlan(String planCode, long priceAmount) {
        PluginPlan plan = new PluginPlan();
        plan.setPid(UlidGenerator.generate());
        plan.setPluginId(testPluginId);
        plan.setPlanCode(planCode);
        plan.setDisplayNameEn("Test Plan");
        plan.setBillingType("one_time");
        plan.setPriceAmount(priceAmount);
        plan.setPriceCurrency("usd");
        plan.setTrialDays(30);
        planMapper.insert(plan);
        return plan;
    }

    private CheckoutRequest buildRequest(String planCode) {
        CheckoutRequest req = new CheckoutRequest();
        req.setPluginId(testPluginId);
        req.setPlanCode(planCode);
        req.setBillingType("one_time");
        return req;
    }

    // ========== Tests ==========

    @Test
    void shouldCreateCheckout_withValidPlan() throws Exception {
        // Arrange
        String planCode = "pro-" + System.currentTimeMillis();
        PluginPlan plan = seedPaidPlan(planCode, 9900L);

        Session mockSession = mock(Session.class);
        when(mockSession.getId()).thenReturn("cs_test_" + System.currentTimeMillis());
        when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_test_123");

        when(stripeService.createOneTimeCheckoutSession(
                anyString(), eq(9900L), eq("usd"), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(mockSession);
        when(stripeService.getPublishableKey()).thenReturn("pk_test_xxx");

        // Act
        CheckoutResponse response = paymentService.createCheckout(
                getTestTenant().getId(), buildRequest(planCode), "buyer@test.com");

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getOrderPid()).isNotBlank();
        assertThat(response.getCheckoutUrl()).isEqualTo("https://checkout.stripe.com/pay/cs_test_123");
        assertThat(response.getPublishableKey()).isEqualTo("pk_test_xxx");

        // Verify order persisted with PENDING status
        PaymentOrder order = orderMapper.findByPid(response.getOrderPid());
        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo("pending");
        assertThat(order.getAmount()).isEqualTo(9900L);
        assertThat(order.getCurrency()).isEqualTo("usd");
        assertThat(order.getPluginId()).isEqualTo(testPluginId);
        assertThat(order.getPlanCode()).isEqualTo(planCode);
        assertThat(order.getBillingType()).isEqualTo("one_time");
        assertThat(order.getStripeSessionId()).isEqualTo(mockSession.getId());
    }

    @Test
    void shouldRejectCheckout_withInvalidPlan() {
        CheckoutRequest req = buildRequest("nonexistent-plan-" + System.currentTimeMillis());

        assertThatThrownBy(() -> paymentService.createCheckout(
                getTestTenant().getId(), req, "buyer@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Plan not found");
    }

    @Test
    void shouldRejectCheckout_withFreePlan() {
        String planCode = "free-" + System.currentTimeMillis();
        seedPaidPlan(planCode, 0L);

        CheckoutRequest req = buildRequest(planCode);

        assertThatThrownBy(() -> paymentService.createCheckout(
                getTestTenant().getId(), req, "buyer@test.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no valid price");
    }

    @Test
    void shouldHandleCheckoutCompletedWebhook_andActivateEntitlement() throws Exception {
        // Arrange — create plan and checkout order first
        String planCode = "webhook-" + System.currentTimeMillis();
        PluginPlan plan = seedPaidPlan(planCode, 4900L);

        String stripeSessionId = "cs_webhook_" + System.currentTimeMillis();
        Session checkoutSession = mock(Session.class);
        when(checkoutSession.getId()).thenReturn(stripeSessionId);
        when(checkoutSession.getUrl()).thenReturn("https://checkout.stripe.com/pay/cs_webhook");

        when(stripeService.createOneTimeCheckoutSession(
                anyString(), eq(4900L), eq("usd"), anyString(), anyString(), anyString(), anyString()
        )).thenReturn(checkoutSession);
        when(stripeService.getPublishableKey()).thenReturn("pk_test_xxx");

        CheckoutResponse checkoutResp = paymentService.createCheckout(
                getTestTenant().getId(), buildRequest(planCode), "buyer@test.com");
        String orderPid = checkoutResp.getOrderPid();

        // Arrange — build webhook event
        String eventId = "evt_" + System.currentTimeMillis();

        Session webhookSession = mock(Session.class);
        when(webhookSession.getId()).thenReturn(stripeSessionId);
        when(webhookSession.getPaymentIntent()).thenReturn("pi_test_abc");
        when(webhookSession.getCustomer()).thenReturn("cus_test_xyz");

        EventDataObjectDeserializer deserializer = mock(EventDataObjectDeserializer.class);
        when(deserializer.getObject()).thenReturn(Optional.of(webhookSession));

        Event event = mock(Event.class);
        when(event.getId()).thenReturn(eventId);
        when(event.getType()).thenReturn("checkout.session.completed");
        when(event.getDataObjectDeserializer()).thenReturn(deserializer);
        when(event.toJson()).thenReturn("{}");

        // Act
        paymentService.handleWebhookEvent(event);

        // Assert — order updated to PAID
        PaymentOrder updatedOrder = orderMapper.findByPid(orderPid);
        assertThat(updatedOrder).isNotNull();
        assertThat(updatedOrder.getStatus()).isEqualTo("paid");
        assertThat(updatedOrder.getPaidAt()).isNotNull();
        assertThat(updatedOrder.getStripePaymentIntentId()).isEqualTo("pi_test_abc");
        assertThat(updatedOrder.getStripeCustomerId()).isEqualTo("cus_test_xyz");

        // Assert — transaction recorded
        var transactions = transactionMapper.findByOrderPid(orderPid);
        assertThat(transactions).hasSize(1);
        assertThat(transactions.get(0).getStripeEventId()).isEqualTo(eventId);
        assertThat(transactions.get(0).getEventType()).isEqualTo("checkout.session.completed");
        assertThat(transactions.get(0).getStatus()).isEqualTo("succeeded");
        assertThat(transactions.get(0).getAmount()).isEqualTo(4900L);
    }

    @Test
    void shouldIgnoreDuplicateWebhookEvent() {
        // Unknown event type — should not throw
        Event event = mock(Event.class);
        when(event.getId()).thenReturn("evt_unknown_" + System.currentTimeMillis());
        when(event.getType()).thenReturn("some.unknown.event");

        // Act — no exception expected
        paymentService.handleWebhookEvent(event);
    }

    @Test
    void shouldReturnEmptyBillingHistory_forNewTenant() {
        // Use a tenant ID unlikely to have orders
        Long unusedTenantId = System.currentTimeMillis();

        List<PaymentOrderDTO> history = paymentService.getBillingHistory(unusedTenantId);

        assertThat(history).isNotNull();
        assertThat(history).isEmpty();
    }
}
