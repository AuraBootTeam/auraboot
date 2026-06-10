package com.auraboot.framework.billing.catalog.model;

/**
 * Functional category grouping for billing resources.
 *
 * <p>Values are stored verbatim in {@code ab_billing_resource_catalog.category}.
 * Keep in sync with the CHECK constraint in
 * {@code 2026-06-10-billing-resource-catalog.sql}.
 */
public enum ResourceCategory {

    /** Low-code platform resources (apps, forms, pages). */
    LOW_CODE,

    /** Automation / workflow execution resources. */
    AUTOMATION,

    /** AI / LLM resources (tokens, copilot calls, knowledge retrieval). */
    AI,

    /** External integration resources (API calls, connectors). */
    INTEGRATION,

    /** Storage / file resources. */
    STORAGE,

    /** User account / seat resources. */
    ACCOUNT,

    /** Governance / compliance resources (audit retention). */
    GOVERNANCE,

    /** Marketplace / plugin resources. */
    MARKETPLACE,

    /** Self-hosted license resources (instance, node count). */
    LICENSE
}
