package com.auraboot.framework.bpm.chain;

/**
 * Constants shared by the thin SmartEngine serviceTask delegates and the
 * JSON → BPMN converter. Keeping the attribute names and node-type discriminators
 * here prevents magic strings from leaking across the BPM ↔ Designer boundary.
 *
 * @since 7.3.0
 */
public final class BpmServiceTaskConstants {

    /** Designer node type for a serviceTask that invokes the Drools delegate. */
    public static final String NODE_TYPE_RULE_TASK = "rule-task";

    /** Designer node type for a serviceTask that invokes the Notification delegate. */
    public static final String NODE_TYPE_NOTIFICATION_TASK = "notification-task";

    /** Spring bean name used as {@code smart:class} for the Drools delegate. */
    public static final String BEAN_DROOLS_DELEGATE = "droolsServiceTaskDelegate";

    /** Spring bean name used as {@code smart:class} for the Notification delegate. */
    public static final String BEAN_NOTIFICATION_DELEGATE = "notificationServiceTaskDelegate";

    // ==================== smart:* extension attributes ====================

    public static final String ATTR_RULE_CODE = "ruleCode";
    public static final String ATTR_FACTS_VARS = "factsVars";
    public static final String ATTR_EVENT_CODE = "eventCode";
    public static final String ATTR_RECIPIENT_FROM = "recipientFrom";
    public static final String ATTR_TEMPLATE_PARAMS_VARS = "templateParamsVars";

    private BpmServiceTaskConstants() {
        // no instances
    }
}
