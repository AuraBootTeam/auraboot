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
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

class SaasConnectorRegistryTest {

    private SaasConnectorRegistry newRegistry() {
        return new SaasConnectorRegistry(List.of(
                new SalesforceConnectorAdapter(),
                new HubspotConnectorAdapter(
                        mock(SaasHttpClient.class),
                        mock(OAuth2TokenStore.class),
                        new ObjectMapper()),
                new StripeConnectorAdapter(),
                new ShopifyConnectorAdapter(),
                new DingTalkConnectorAdapter()));
    }

    @Test
    void registersAllFiveVendors() {
        SaasConnectorRegistry registry = newRegistry();
        assertThat(registry.size()).isEqualTo(5);
        assertThat(registry.listAll())
                .extracting(a -> a.descriptor().protocolType())
                .containsExactlyInAnyOrder(
                        "saas-salesforce",
                        "saas-hubspot",
                        "saas-stripe",
                        "saas-shopify",
                        "saas-dingtalk");
    }

    @Test
    void lookupSalesforce() {
        assertThat(newRegistry().lookupByVendor("saas-salesforce"))
                .isPresent()
                .get()
                .isInstanceOf(SalesforceConnectorAdapter.class);
    }

    @Test
    void lookupHubspot() {
        assertThat(newRegistry().lookupByVendor("saas-hubspot"))
                .isPresent()
                .get()
                .isInstanceOf(HubspotConnectorAdapter.class);
    }

    @Test
    void lookupStripe() {
        assertThat(newRegistry().lookupByVendor("saas-stripe"))
                .isPresent()
                .get()
                .isInstanceOf(StripeConnectorAdapter.class);
    }

    @Test
    void lookupShopify() {
        assertThat(newRegistry().lookupByVendor("saas-shopify"))
                .isPresent()
                .get()
                .isInstanceOf(ShopifyConnectorAdapter.class);
    }

    @Test
    void lookupDingTalk() {
        assertThat(newRegistry().lookupByVendor("saas-dingtalk"))
                .isPresent()
                .get()
                .isInstanceOf(DingTalkConnectorAdapter.class);
    }

    @Test
    void lookupReturnsEmptyForUnknownVendor() {
        SaasConnectorRegistry registry = newRegistry();
        assertThat(registry.lookupByVendor("saas-bogus")).isEmpty();
        assertThat(registry.lookupByVendor(null)).isEmpty();
    }

    @Test
    void duplicateRegistrationRejected() {
        SaasConnectorRegistry registry = newRegistry();
        assertThatThrownBy(() -> registry.register(new SalesforceConnectorAdapter()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saas-salesforce");
    }

    @Test
    void nullAdapterRejected() {
        assertThatThrownBy(() -> newRegistry().register(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
