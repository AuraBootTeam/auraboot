/**
 * IoT-specific automation action executors layered on top of the existing
 * {@link com.auraboot.framework.automation.executor.ActionExecutor} SPI.
 *
 * <p>Spike scope (ROI #5 of {@code iot-auraboot-soft-book.md}): instead of
 * re-implementing a ThingsBoard-style Rule Chain actor system, IoT rule
 * primitives (filter / enrich / transform / act) are expressed as four
 * additional {@code ActionExecutor} beans. The existing SmartEngine
 * orchestration (gateways, sequence flows, loops) is reused unchanged.
 *
 * <ul>
 *   <li>{@code iot_filter} — drop the run unless device / product / tenant /
 *       SpEL predicate matches.</li>
 *   <li>{@code iot_enrichment} — merge device + product metadata onto the
 *       execution context for downstream nodes.</li>
 *   <li>{@code iot_transformation} — derive new context variables via SpEL
 *       expressions (math, unit conversion, simple aggregations).</li>
 *   <li>{@code iot_action} — emit an outcome: publish a Kafka envelope
 *       ({@code iot.alarm.v1} / {@code iot.cmd.req.v1}), invoke an
 *       AuraBoot command, or fire a follow-on automation.</li>
 * </ul>
 *
 * <p>The four classes do not depend on each other and are composed purely
 * via the SmartEngine flow graph; this keeps the rule definition declarative
 * and the executors independently unit-testable.
 */
package com.auraboot.framework.automation.iot;
