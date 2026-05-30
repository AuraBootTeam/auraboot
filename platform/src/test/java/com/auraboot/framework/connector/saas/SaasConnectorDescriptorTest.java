package com.auraboot.framework.connector.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auraboot.framework.connector.saas.dingtalk.DingTalkConnectorAdapter;
import com.auraboot.framework.connector.saas.hubspot.HubspotConnectorAdapter;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.oauth.OAuth2TokenStore;
import com.auraboot.framework.connector.saas.salesforce.SalesforceConnectorAdapter;
import com.auraboot.framework.connector.saas.shopify.ShopifyConnectorAdapter;
import com.auraboot.framework.connector.saas.stripe.StripeConnectorAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/**
 * Per-vendor descriptor + scaffold assertion tests.
 *
 * <p>Each scaffold must (a) return the canonical vendor key and stream list, and
 * (b) clearly fail with {@code UnsupportedOperationException("NOT_YET_IMPLEMENTED")}
 * from {@code discover()} / {@code read()} so the sync engine can detect the
 * "scaffold only" state at runtime.
 */
class SaasConnectorDescriptorTest {

    @Test
    void salesforceDescriptor() {
        SalesforceConnectorAdapter a = new SalesforceConnectorAdapter();
        assertThat(a.descriptor().protocolType()).isEqualTo("saas-salesforce");
        assertThat(a.descriptor().supportedEndpointCodes())
                .containsExactly("Account", "Contact", "Opportunity", "Lead", "Case", "User");
        assertThat(a.supports("SAAS-SALESFORCE")).isTrue();
        assertThatThrownBy(() -> a.discover(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("NOT_YET_IMPLEMENTED");
        assertThatThrownBy(() -> a.read(null, "Account", null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("NOT_YET_IMPLEMENTED");
        assertThat(a.testConnection(1L, "pid")).isFalse();
    }

    @Test
    void hubspotDescriptor() {
        // HubSpot has graduated from scaffold to real implementation (W5-M2.1).
        // Inject mocked deps; behavioural coverage lives in HubspotConnectorAdapterTest.
        HubspotConnectorAdapter a = new HubspotConnectorAdapter(
                mock(SaasHttpClient.class), mock(OAuth2TokenStore.class), new ObjectMapper());
        assertThat(a.descriptor().protocolType()).isEqualTo("saas-hubspot");
        assertThat(a.descriptor().supportedEndpointCodes())
                .containsExactly("companies", "contacts", "deals", "tickets", "line_items", "products");
    }

    @Test
    void stripeDescriptor() {
        StripeConnectorAdapter a = new StripeConnectorAdapter();
        assertThat(a.descriptor().protocolType()).isEqualTo("saas-stripe");
        assertThat(a.descriptor().supportedEndpointCodes())
                .containsExactly("customers", "charges", "invoices", "subscriptions", "events");
        assertThatThrownBy(() -> a.discover(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> a.read(null, "charges", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shopifyDescriptor() {
        ShopifyConnectorAdapter a = new ShopifyConnectorAdapter();
        assertThat(a.descriptor().protocolType()).isEqualTo("saas-shopify");
        assertThat(a.descriptor().supportedEndpointCodes())
                .containsExactly("orders", "customers", "products", "inventory_items", "fulfillments");
        assertThatThrownBy(() -> a.discover(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> a.read(null, "orders", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void dingtalkDescriptor() {
        DingTalkConnectorAdapter a = new DingTalkConnectorAdapter();
        assertThat(a.descriptor().protocolType()).isEqualTo("saas-dingtalk");
        assertThat(a.descriptor().supportedEndpointCodes())
                .containsExactly("users", "departments", "attendance", "approvals", "contacts", "im_messages");
        assertThatThrownBy(() -> a.discover(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> a.read(null, "users", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void invokeReturnsFailureStub() {
        SalesforceConnectorAdapter a = new SalesforceConnectorAdapter();
        var result = a.invoke(null);
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("NOT_YET_IMPLEMENTED").contains("saas-salesforce");
    }

    @Test
    void supportedStreamsMatchesDescriptor() {
        HubspotConnectorAdapter a = new HubspotConnectorAdapter(
                mock(SaasHttpClient.class), mock(OAuth2TokenStore.class), new ObjectMapper());
        assertThat(a.supportedStreams())
                .isEqualTo(a.descriptor().supportedEndpointCodes());
    }
}
