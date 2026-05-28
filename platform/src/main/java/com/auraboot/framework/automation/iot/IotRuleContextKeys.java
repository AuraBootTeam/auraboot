package com.auraboot.framework.automation.iot;

/**
 * Canonical process-variable keys shared by the IoT rule node family.
 *
 * <p>The keys are deliberately stable strings so a rule authored in the
 * designer JSON can be matched 1:1 against runtime evidence captured by the
 * sink in tests (or by an observer in production).
 */
public final class IotRuleContextKeys {

    private IotRuleContextKeys() {}

    /** Original telemetry payload as received from the data plane. */
    public static final String TELEMETRY = "telemetry";

    /** Device identifier carried by the telemetry envelope. */
    public static final String DEVICE_ID = "deviceId";

    /** Product identifier resolved from the device record. */
    public static final String PRODUCT_ID = "productId";

    /** Tenant boundary, populated by the trigger. */
    public static final String TENANT_ID = "tenantId";

    /** Marker variable set by {@link IotFilterNode} when the run should stop.
     *  Name avoids a leading underscore so SmartEngine condition expressions
     *  (e.g. {@code iotDropped == false}) can reference it through SpEL. */
    public static final String DROPPED = "iotDropped";

    /** Optional reason explaining why the filter dropped the run. */
    public static final String DROP_REASON = "iotDropReason";

    /** Metadata merged in by {@link IotEnrichmentNode}, keyed by lookup type. */
    public static final String DEVICE_META = "deviceMeta";

    /** Product metadata merged in by {@link IotEnrichmentNode}. */
    public static final String PRODUCT_META = "productMeta";

    /** Outcomes published by {@link IotActionNode}; appended per invocation. */
    public static final String ACTION_OUTCOMES = "iotActionOutcomes";
}
