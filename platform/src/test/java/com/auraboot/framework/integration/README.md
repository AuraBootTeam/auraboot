# Device Model Integration Tests

## 概述

这是AuraBoot平台的完整集成测试套件，通过创建一个Device Model来验证平台的核心功能链路。测试连接真实数据库，不使用Mock，确保所有组件在真实环境下的协同工作。

## 测试架构

### 测试类结构

```
integration/
├── DeviceModelIntegrationTestSuite.java    # 测试套件入口
├── DeviceModelTestHelpers.java             # 测试辅助方法
├── dict/
│   └── DictIntegrationTest.java            # 数据字典功能测试
├── model/
│   └── ModelIntegrationTest.java           # 业务模型功能测试
├── dynamic/
│   └── DynamicTableIntegrationTest.java    # 动态建表功能测试
├── crud/
│   └── CrudIntegrationTest.java            # CRUD操作功能测试
├── permission/
│   └── PermissionIntegrationTest.java      # 权限系统功能测试
├── query/
│   └── QueryIntegrationTest.java           # 查询功能测试
├── menu/
│   └── MenuPermissionIntegrationTest.java  # 菜单权限功能测试
├── performance/
│   └── PerformanceIntegrationTest.java     # 性能测试
└── exception/
    └── ExceptionHandlingIntegrationTest.java # 异常处理测试
```

### 测试执行顺序

1. **DictIntegrationTest** - 创建数据字典（静态字典和动态级联字典）
2. **ModelIntegrationTest** - 创建业务模型和字段定义
3. **DynamicTableIntegrationTest** - 通过API调用创建数据库表结构
4. **CrudIntegrationTest** - 测试数据的增删改查操作
5. **PermissionIntegrationTest** - 测试权限系统功能
6. **QueryIntegrationTest** - 测试查询功能和SQL注入防护
7. **MenuPermissionIntegrationTest** - 测试菜单权限集成
8. **PerformanceIntegrationTest** - 测试系统性能
9. **ExceptionHandlingIntegrationTest** - 测试异常处理

## 运行测试

### 运行完整测试套件

```bash
cd platform
./gradlew test --tests "DeviceModelIntegrationTestSuite" --info
```

### 运行单个测试类

```bash
# 运行数据字典测试
./gradlew test --tests "DictIntegrationTest" --info

# 运行CRUD测试
./gradlew test --tests "CrudIntegrationTest" --info

# 运行权限测试
./gradlew test --tests "PermissionIntegrationTest" --info
```

### 运行特定测试方法

```bash
# 运行特定测试方法
./gradlew test --tests "DictIntegrationTest.test01_createDeviceTypeDict" --info
```

## 测试配置

### 数据库配置

测试使用 `integration-test` profile，配置在 `application-integration-test.yml` 中：

```yaml
spring:
  profiles:
    active: integration-test
  datasource:
    # 使用默认数据库配置
```

### 租户配置

测试使用数据库中的真实租户和用户：

```java
private static final Long TEST_TENANT_ID = 231716683964878848L;
private static final String TEST_USER_PID = "01K6FKASY1R3CAR53PPR9X19P3";
```

## 测试特点

### 1. 真实环境测试

- **不使用Mock**：连接真实数据库和服务
- **真实API调用**：通过Controller层测试完整链路

### 2. 严格验证

- **不捕获异常**：让真实错误暴露，识别未实现功能
- **严格断言**：验证业务逻辑正确性，不仅仅检查null
- **完整验证**：验证数据库持久化、缓存效果、权限控制等

### 3. 业务场景覆盖

- **完整业务流程**：从字典创建到数据操作的完整链路
- **权限控制**：实体级、字段级、字典级权限验证
- **性能测试**：缓存、并发、大数据量等场景
- **异常处理**：各种异常情况和边界条件

## Device Model 设计

### 实体结构

```json
{
  "entityCode": "device",
  "entityName": "设备管理",
  "fields": [
    {"fieldCode": "device_id", "dataType": "STRING", "required": true, "primaryKey": true},
    {"fieldCode": "device_name", "dataType": "STRING", "required": true},
    {"fieldCode": "device_type", "dataType": "STRING", "dictBinding": "device_type_dict"},
    {"fieldCode": "manufacturer", "dataType": "STRING", "dictBinding": "manufacturer_dict"},
    {"fieldCode": "status", "dataType": "STRING", "dictBinding": "device_status_dict"},
    {"fieldCode": "install_date", "dataType": "DATE", "required": true},
    {"fieldCode": "warranty_expire", "dataType": "DATE"},
    {"fieldCode": "price", "dataType": "DECIMAL", "sensitive": true},
    {"fieldCode": "serial_number", "dataType": "STRING", "unique": true, "sensitive": true},
    {"fieldCode": "description", "dataType": "TEXT"}
  ]
}
```

### 数据字典

1. **设备类型字典**（静态）：SENSOR, ACTUATOR, CONTROLLER, GATEWAY
2. **制造商字典**（静态）：SIEMENS, ABB, SCHNEIDER, HONEYWELL
3. **设备状态字典**（静态）：ONLINE, OFFLINE, MAINTENANCE, FAULT
4. **安装位置字典**（动态级联）：按制造商级联的位置信息

### 权限配置

- **设备管理员**：所有权限
- **设备操作员**：查看、创建、更新、导出权限，价格字段编辑拒绝
- **设备查看者**：只读权限，敏感字段拒绝/脱敏

## 测试结果分析

### 成功指标

1. **数据字典**：4个字典成功创建，版本策略正确工作
2. **业务模型**：设备模型和11个字段成功创建
3. **动态建表**：device表成功创建，可以正常CRUD
4. **权限系统**：3个角色权限正确配置和生效
5. **查询功能**：NamedQuery正确执行，SQL注入防护生效
6. **性能指标**：缓存命中率>80%，查询响应时间<100ms

### 失败分析

测试失败通常指示以下问题：

1. **服务未实现**：相关Service或Controller不存在
2. **数据库表缺失**：需要的表结构未创建
3. **权限配置错误**：权限系统配置不正确
4. **API接口问题**：接口参数或返回值不匹配
5. **业务逻辑错误**：业务规则实现有问题

## 故障排除

### 常见问题

1. **"device表不存在"**
   - 运行 `DynamicTableIntegrationTest` 创建表结构
   - 检查动态建表API是否实现

2. **权限验证失败**
   - 检查权限系统是否正确配置
   - 验证用户角色分配是否正确

3. **数据验证失败**
   - 检查字段验证规则是否实现
   - 验证数据类型转换是否正确

4. **查询执行失败**
   - 检查NamedQuery服务是否实现
   - 验证SQL模板是否正确

### 调试建议

1. **查看详细日志**：使用 `--info` 参数运行测试
2. **单步调试**：运行单个测试方法进行调试
3. **检查数据库**：验证数据是否正确创建
4. **查看异常堆栈**：分析具体的错误原因

## 扩展测试

### 添加新测试

1. 在相应的测试类中添加新的测试方法
2. 使用 `@Order` 注解指定执行顺序
3. 遵循命名规范：`testXX_功能描述`
4. 添加详细的注释说明测试目的

### 添加新测试类

1. 在相应的包下创建新的测试类
2. 继承相同的测试配置和注解
3. 在 `DeviceModelIntegrationTestSuite` 中添加引用
4. 更新本README文档

## 参考文档

- [集成测试计划](../../../../../../../../.kiro/specs/dict-rbac-git-platform/integration-test-plan.md)
- [AuraBoot架构文档](../../../../../../../../docs/documents/AuraBootDesignDocs_V1.0.md)
- [Spring Boot测试指南](https://spring.io/guides/gs/testing-web/)
- [JUnit 5用户指南](https://junit.org/junit5/docs/current/user-guide/)