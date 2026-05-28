# IoT Rule Nodes (Automation + SmartEngine)

> Status: M0 platform primitives shipped 2026-05-28. Enterprise plugin wiring (Kafka/BPM sinks,
> ent-iot-control accessors, designer palette + i18n labels) is owned by the M1 IoT slice.

AuraBoot reuses the existing SmartEngine-backed automation runtime for IoT rule processing
instead of standing up a ThingsBoard-style rule chain actor system. Four `ActionExecutor`
beans cover the canonical IoT primitives — filter, enrichment, transformation, action — and
SmartEngine's existing sequence flow / exclusive gateway / loop semantics express the rest.

## Action types

| `actionType`          | Bean                       | Purpose |
|-----------------------|----------------------------|---------|
| `iot_filter`          | `IotFilterNode`            | Drop the run unless device / product / tenant scope or a SpEL predicate matches. Sets `iotDropped` + `iotDropReason` on the process variables. |
| `iot_enrichment`      | `IotEnrichmentNode`        | Merge device + product metadata onto the context via `BackgroundDeviceAccessor` / `BackgroundProductAccessor` SPIs (no-op when no plugin is registered). Auto-promotes `productId` from device meta. |
| `iot_transformation`  | `IotTransformationNode`    | Evaluate ordered SpEL assignments to derive new context variables (unit conversion, threshold flags, etc.). |
| `iot_action`          | `IotActionNode`            | Emit an envelope (`alarm` / `command` / `record` / `workflow`) to every registered `IotActionSink` (Kafka, BPM start, in-memory recorder, …). |

All four nodes short-circuit if `iotDropped == true`, providing defense in depth in case the
rule omits the gateway.

## Wiring contract

- Compiler: `control-condition` JSON nodes compile to BPMN `exclusiveGateway`. Every outgoing
  flow **must** carry a non-empty `data.condition.content` — the SmartEngine fork does not
  honor BPMN default-flow fallback. Use an explicit complement such as
  `iotDropped == true` on the drop branch.
- Variable propagation: `executionContext.getRequest()` is the same map the action receives;
  mutations flowed by `IotFilterNode` / `IotEnrichmentNode` / `IotTransformationNode` are
  visible to downstream gateway condition evaluators without any explicit `setVariable` call.
  This was verified by `IotRuleSmartEngineIntegrationTest`.
- SpEL safety: the evaluator runs under `SimpleEvaluationContext.forReadOnlyDataBinding()`.
  This blocks arbitrary method calls. Use the SpEL indexer for nested map reads:
  `#{#deviceMeta?.['site']}` instead of `#{deviceMeta?.get('site')}`.

## Sample rule

See `platform/src/test/resources/automation/iot/temp-alarm-rule.json` for a full
trigger → enrich → filter → gateway → transform → gateway → alarm flow.

Recommended node order for production rules:
1. **Trigger** (`trigger-iot-telemetry` or whatever event source the IoT plugin registers)
2. **Enrichment** — pulls product/device meta so subsequent filter can scope by product
3. **Filter** — applies product/device/tenant/predicate scoping
4. **Gateway** branching on `iotDropped == false` / `iotDropped == true`
5. **Transformation** — derive Celsius / threshold / aggregations
6. **Gateway** branching on business condition (e.g. `isHighTemp == true`)
7. **Action** — fans out to sinks

## Sink registration

`IotActionSink` is a Spring `@Bean` SPI. The OSS core ships only the interface and the
in-memory test sink. Production sinks (Kafka producer for `iot.alarm.v1` /
`iot.cmd.req.v1`, BPM `ProcessEngineService.startProcess`, AuraBoot command bus) live in
`ent-iot-control` and downstream plugins. Multiple sinks may coexist — every outcome is
fanned out to all registered beans.

## Background accessor SPIs

`BackgroundDeviceAccessor` and `BackgroundProductAccessor` mirror the
`BackgroundTenantAccessor` / `BackgroundConnectorCredentialAccessor` pattern locked
2026-05-27. The OSS automation package ships only the interfaces; the IoT plugin
(`ent-iot-control`) implements them against its own `mt_iot_device` / `mt_iot_product`
tables. With no implementation registered, enrichment is a no-op for that dimension and
the rest of the rule still runs.

## Known SmartEngine fork constraints

- Multi-instance loops only expand for `userTask` (会签). For collection-driven
  fan-out, iterate inside the action delegate (see `AutomationActionServiceTaskDelegate`
  loop handling). For high-throughput device fan-out, prefer Kafka-partition parallelism
  over rule-internal loops.
- Every exclusive gateway outgoing flow must carry a condition. `isDefault: true` is
  rejected; use a complementary expression instead.
