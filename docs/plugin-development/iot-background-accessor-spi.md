---
type: product-doc
status: active
---

# IoT Background Accessor SPI

> 受众:在 AuraBoot 上开发 IoT 控制平面/数据面 plugin(如企业版 `ent-iot-control`)的开发者。
> 模块:`platform-plugin-api` → `com.auraboot.framework.plugin.extension.iot`。
> 上游模式:`BackgroundConnectorCredentialAccessor`、`BackgroundDataAccessor`、`BackgroundTenantAccessor`(crawler V2 三 SPI)。

## 1. 概述

为 IoT plugin 提供 4 个**只读 / 凭据下发**桥接接口,让 plugin 的 background 组件(`@KafkaListener`、`@Scheduled`、EMQX webhook 消费者、规则引擎 worker、告警路由)能在不耦合 platform 内部 service 类型的前提下,完成:

| SPI | 用途 |
|-----|------|
| `BackgroundDeviceAccessor` | 按 `deviceCode` / `iotId` 查设备 → 拿到 tenant / product / ACL / 状态 |
| `BackgroundProductAccessor` | 按 `productKey` 查产品头部 + TSL schema(properties + events + services) |
| `BackgroundRuleAccessor` | 按 scope(device / product / tenant)枚举生效规则,或按 `ruleCode` 精确查 |
| `BackgroundIotCredentialAccessor` | 下发 / 撤销设备凭据,触发 EMQX ACL 同步 |

## 2. 共同契约(与 crawler V2 三 SPI 完全一致)

- **可选注入**:plugin 用 `@Autowired(required = false)`,字段类型本 SPI 接口;**老 platform 不实现该 SPI → 字段为 `null` → plugin 走文档化降级行为**(例如把 ACL 同步跳过、把 TSL 校验跳过)。
- **多租户隔离**:除 `lookupByIotId(String)`、`getSchema(String)` 外,所有方法都强制 `long tenantId` 入参。`iotId` 全局唯一;`productKey` 由 platform 限定全平台唯一(便于反查 schema)。返回对象都带 `tenantId` 字段,plugin 后续读取必须 scope 回该租户。
- **不可变快照**:所有视图都用 Java 21 `record`,字段不可变;无 setter。新字段以"追加 record 组件"形式在未来 minor 版本扩展。
- **空集语义**:`findActiveByScope` 等返回 `List<T>`,无匹配返回**空列表而非 null**;`lookup*` / `findByCode` 返回 `Optional<T>`,无匹配返回 `Optional.empty()`。
- **不抛 checked 异常**;DB / broker 故障由 platform 实现包装为 `RuntimeException`。

## 3. 接口签名速查

```java
public interface BackgroundDeviceAccessor {
    Optional<DeviceView> lookupByCode(long tenantId, String deviceCode);
    Optional<DeviceView> lookupByIotId(String iotId);
    record DeviceView(String iotId, String deviceCode, String productKey,
                      long tenantId, String status, String aclPattern,
                      Map<String,String> tags, Instant lastSeenAt) {}
}

public interface BackgroundProductAccessor {
    Optional<ProductView> lookupByKey(long tenantId, String productKey);
    Optional<ProductSchema> getSchema(String productKey);
    record ProductView(String productKey, Map<String,String> name,
                       String nodeType, String dataFormat, String transportType,
                       long tenantId) {}
    record ProductSchema(List<PropertyDef> properties, List<EventDef> events,
                         List<ServiceDef> services) {}
    record PropertyDef(String identifier, String dataType, boolean required,
                       String unit, Map<String,Object> range) {}
    record EventDef(...) ; record ServiceDef(...) ;
}

public interface BackgroundRuleAccessor {
    List<RuleView> findActiveByScope(long tenantId, RuleScope scope, String scopeKey);
    Optional<RuleView> findByCode(long tenantId, String ruleCode);
    enum RuleScope { DEVICE, PRODUCT, TENANT }
    enum RuleKind  { SQL, CHAIN, SMART_ENGINE }
    record RuleView(String code, RuleScope scope, String scopeKey,
                    RuleKind kind, String expression, String actions,
                    String severity, int cooldownSeconds, boolean enabled,
                    long tenantId) {}
}

public interface BackgroundIotCredentialAccessor {
    IotCredentials issueCredentials(long tenantId, String deviceCode, CredentialType type);
    void revokeCredentials(long tenantId, String deviceCode);
    void syncAclToBroker(long tenantId);
    enum CredentialType { ACCESS_TOKEN, X509_CERTIFICATE, MQTT_BASIC, JWT }
    record IotCredentials(CredentialType type, String secret, String jwt,
                          List<String> aclPatterns, Instant expiresAt) {}
}
```

## 4. plugin 端用法

### 4.1 注入

```java
@Component
public class IotRuleEvaluator {
    @Autowired(required = false)
    private BackgroundDeviceAccessor deviceAccessor;

    @Autowired(required = false)
    private BackgroundProductAccessor productAccessor;

    @Autowired(required = false)
    private BackgroundRuleAccessor ruleAccessor;

    @Autowired(required = false)
    private BackgroundIotCredentialAccessor credentialAccessor;
}
```

### 4.2 降级策略

- `deviceAccessor == null` → plugin 用本地 cache 兜底,或拒绝处理 telemetry 并告警;**不要静默丢消息**。
- `productAccessor == null` → 跳过 TSL 校验,但**必须**在 metrics 打 `iot.product_accessor.unavailable` 计数,运维感知。
- `ruleAccessor == null` → 不评估规则,直接转发原始 telemetry。
- `credentialAccessor == null` → MQTT ACL 同步降级为人工触发(打 log 提示)。

### 4.3 典型调用顺序(规则引擎 worker)

```java
public void onTelemetry(TelemetryFrame frame) {
    if (deviceAccessor == null) return;
    DeviceView dev = deviceAccessor.lookupByIotId(frame.iotId())
            .orElseThrow(() -> new IllegalStateException("unknown device " + frame.iotId()));
    long tenantId = dev.tenantId();

    if (ruleAccessor != null) {
        List<RuleView> rules = ruleAccessor.findActiveByScope(
                tenantId, RuleScope.DEVICE, dev.deviceCode());
        rules.forEach(r -> evaluate(r, frame, dev));
    }
}
```

## 5. 测试责任划分

| 层 | 谁负责 | 怎么测 |
|---|---|---|
| SPI 契约形状 | OSS `platform-plugin-api` 仓 | `*AccessorContractTest`(in-memory fake,JUnit 5 + AssertJ) — 已就位 |
| platform 实现 | OSS / 企业版 platform 实现类 | Spring Boot integration test,真 PG,验证多租户 SQL where 条件 |
| plugin 端降级 | plugin 自身仓 | Mockito,断言 `null` 注入时不抛、改走 fallback |
| 端到端 | 企业版 `ent-iot-control` plugin(M1.B-E) | docker isolated stack,真 EMQX,完整 telemetry → 规则 → 告警链路 |

## 6. 与 crawler V2 三 SPI 的对照

| 维度 | crawler V2 | IoT(本文) |
|------|-----------|-----------|
| Package | `com.auraboot.framework.plugin.extension` | `com.auraboot.framework.plugin.extension.iot`(子包) |
| 多租户参数 | `BackgroundTenantAccessor.listActiveTenantIds()` 给定全局枚举 | 每方法 `long tenantId` 入参 |
| 凭据 | `BackgroundConnectorCredentialAccessor`(只读) | `BackgroundIotCredentialAccessor`(读 + 写 + ACL 同步) |
| 接收方 | Python render worker / Java fetch | Java EMQX hook listener / 规则 worker / 告警路由 |
| `@since` | 2.5.0 | 2.6.0 |

## 7. 落地状态

- **接口 + 测试**:本 PR `feat(plugin-api): add 4 IoT Background*Accessor SPI`,4 文件 + 4 contract tests(25 用例全绿)。
- **platform 实现**:待 M1.A 后续 PR 或 enterprise overlay 落地;实现前 plugin 端注入字段始终为 `null`,plugin 走降级。
- **plugin 接入**:M1.B-E `ent-iot-control` plugin 消费本 SPI。

## 8. M1.B-E 接入 checklist

落 `ent-iot-control` 时按下表逐项核对:

- [ ] 在 plugin Spring 配置里用 `@Autowired(required = false)` 注入 4 个 accessor。
- [ ] 给每个 accessor 写 `@Nullable` + javadoc,明确 null 时 plugin 的降级行为。
- [ ] 在 plugin metrics 注册 4 个 gauge:`iot.device_accessor.available`、`iot.product_accessor.available`、`iot.rule_accessor.available`、`iot.credential_accessor.available`(0/1)。
- [ ] 单元测试覆盖"accessor 注入为 null"路径,断言不抛 NPE。
- [ ] 集成测试用 Mockito 提供 stub 实现,跑通完整 telemetry → 规则 → 告警链路。
- [ ] **禁止**在 plugin 内重新声明 `Background*Accessor` 接口或 view record;一律 `import com.auraboot.framework.plugin.extension.iot.*`。

## 9. TimeSeriesPort(M1.A.3 补漏 SPI,`@since 2.6.1`)

> M1.A 初次 drop(`b5927485`)只合了 4 个 `Background*Accessor`,**遗漏了 TimeSeriesPort**。
> 这是「SPI 接口/impl 必须同 PR」反面教材:plan §2 + §5 都明确写了 TimeSeriesPort,
> 但 SPI 接口和 platform impl 拆 PR 时漏补。M1.A.3 PR 把接口 + impl + IT 同一 PR 收口。

### 9.1 接口签名

```java
public interface TimeSeriesPort {
    void writeBatch(long tenantId, List<TimeSeriesPoint> points);

    List<TimeSeriesPoint> queryLatest(
            long tenantId, String deviceCode, List<String> codes, int limit);

    List<TimeSeriesPoint> queryRange(long tenantId, QueryParams.Range params);

    List<AggregatedPoint> queryAggregate(long tenantId, QueryParams.Aggregate params);
}

// DTO (records, package com.auraboot.framework.plugin.extension.iot)
record TimeSeriesPoint(String deviceCode, String code, Instant ts, Number value, String qualityCode) {}
record AggregatedPoint(String deviceCode, String code, Instant bucketStart, Number value, Long pointCount) {}

final class QueryParams {
    record Range(String deviceCode, List<String> codes,
                 Instant from, Instant to, Duration downsample /* null = no downsample */) {}
    record Aggregate(String deviceCode, List<String> codes,
                     Instant from, Instant to,
                     Aggregation aggregation, Duration groupBy) {}
    enum Aggregation { AVG, MAX, MIN, SUM, COUNT, FIRST, LAST }
}
```

### 9.2 契约要点(在通用 §2 之上)

- **多租户严格**:每个方法都强制 `long tenantId` 入参,实现层 SQL `WHERE tenant_id = ?` 不可少 — M0 `iot/core/ts-port/` 没有 tenantId 字段(routing 在 wire time),platform SPI 不接受这种设计。
- **聚合下推**:`queryRange(downsample!=null)` 和 `queryAggregate` 必须翻译成 vendor-native SQL(TDengine `INTERVAL(N) FILL(LINEAR)`、TimescaleDB `time_bucket_gapfill()`);**禁止** client-side aggregation。
- **空 bucket 不补零**:`queryAggregate` 返回的列表只含非空 bucket,UI 决定如何渲染空隙。
- **降级模式**:`iot.tdengine.enabled=false`(默认)时不注册 bean,plugin 字段为 null → 走 DLQ batch / skip telemetry / metric-only。

### 9.3 配置开关(platform impl `TDengineTimeSeriesPort`)

```yaml
iot:
  tdengine:
    enabled: true                  # 不开启时不注册 bean,plugin 端字段为 null
    url: jdbc:TAOS-RS://host:6041/?user=root&password=taosdata&batchfetch=true
    username: root                 # 默认 root
    password: taosdata             # 默认 taosdata
    maxPoolSize: 10                # 默认 10
```

`iot.tdengine.enabled=true` 但 `iot.tdengine.url` 缺失会 fail-fast(`@Value` placeholder unresolved → context 启动失败)。这是有意设计:任何 deploy 开了 IoT 却忘配 TDengine 应该立刻崩,而不是默默吞掉每条设备样本。

### 9.4 plugin 端用法

```java
@Component
public class TelemetryConsumer {
    @Autowired(required = false)
    private TimeSeriesPort tsport;

    @KafkaListener(topics = "iot.telemetry")
    public void onTelemetry(TelemetryBatch batch) {
        if (tsport == null) {
            // documented degradation: send to DLQ + metric
            dlq.send(batch);
            meter.counter("iot.tsport.unavailable").increment();
            return;
        }
        tsport.writeBatch(batch.tenantId(), batch.toPoints());
    }
}
```

### 9.5 与 M0 `iot/core/ts-port/` 的关系

M0 `iot/core/ts-port/` 在独立 Java multi-module(`/Users/ghj/work/auraboot/iot/`)内有同名接口,签名是 single-tenant(routing 在 wire time)。
**M1.A.3 不影响 M0**(grep 显示 M0 ts-port 不被 `iot/core/rule-worker` 或 `iot/data-plane` 引用,是 standalone 模块)。
后续如 M0 模块要切到 canonical SPI,要做 import 路径迁移并补 `tenantId` 参数;**这属于 iot/ 仓的 deprecation PR,不在本 PR scope**。

### 9.6 测试

- `TimeSeriesPortContractTest`(plugin-api 16 用例)— 跑 `InMemoryTimeSeriesPort`,覆盖 writeBatch / queryLatest / queryRange(raw + downsampled) / queryAggregate(AVG/COUNT) + 跨租户隔离 + 所有 validation 边界。
- `TDengineTimeSeriesPortUnitTest`(platform 8 用例,Mockito DataSource)— 子表名稳定性 + 输入校验。
- `TDengineTimeSeriesPortIT`(platform 2 用例,**opt-in via `IOT_TDENGINE_IT_TESTCONTAINERS=true`**)— Testcontainers 启 `tdengine/tdengine:3.3.4.3`,跑 10K 样本 scale + 跨租户严格隔离。
