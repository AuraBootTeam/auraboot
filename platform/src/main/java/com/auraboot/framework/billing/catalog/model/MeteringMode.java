package com.auraboot.framework.billing.catalog.model;

/**
 * How usage for a resource is measured.
 *
 * <p>Values are stored verbatim in {@code ab_billing_resource_catalog.metering_mode}.
 * Keep in sync with the CHECK constraint in
 * {@code 2026-06-10-billing-resource-catalog.sql}.
 */
public enum MeteringMode {

    /** Usage is captured as a point-in-time count (e.g. seat, app_count). */
    SNAPSHOT,

    /** Usage is captured per discrete event (e.g. API call, workflow execution). */
    EVENT,

    /** Value is set by configuration rather than measured (e.g. audit_retention_day). */
    CONFIG,

    /** Usage is reported by a heartbeat ping (e.g. self-hosted instance/node count). */
    HEARTBEAT
}
