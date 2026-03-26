# BFF代理服务增强日志指南

## 📋 概述

BFF代理服务现在提供了更详细和结构化的日志记录，帮助开发者更好地调试和监控API请求。

## 🎯 日志增强功能

### 1. **结构化日志格式**
- 使用emoji图标快速识别日志类型
- JSON格式的详细信息便于解析和搜索
- 包含请求ID用于跟踪完整的请求生命周期

### 2. **详细的错误分类**
- 🔐 **401 认证错误**: JWT token相关问题
- 🚫 **403 授权错误**: 权限不足问题  
- 🔍 **404 未找到**: API端点不存在
- 🔥 **5xx 服务器错误**: 后端服务内部错误
- 🔌 **连接拒绝**: 后端服务不可用
- ⏰ **网络超时**: 连接或DNS问题

### 3. **敏感信息保护**
- JWT token只显示前10位和后4位字符
- Cookie信息被标记为 `[REDACTED]`
- 密码等敏感字段自动过滤

## 📊 日志示例

### 成功请求日志
```
✅ API Proxy Success [1766058761298-580n10128] {
  "requestId": "1766058761298-580n10128",
  "method": "GET",
  "url": "/api/admin/documents",
  "status": 200,
  "duration": "245ms",
  "responseSize": "2.3KB",
  "userAgent": "Mozilla/5.0...",
  "clientIp": "127.0.0.1"
}
```

### 认证错误日志
```
🔐 Authentication Error [ERR_BAD_REQUEST] - GET /api/admin/documents {
  "status": 401,
  "message": "JWT token expired",
  "responseData": {
    "error": "Unauthorized",
    "message": "Token has expired"
  },
  "headers": {
    "authorization": "Bearer eyJhbGciOi...xyz1"
  },
  "suggestion": "Check JWT token validity, expiration, or authentication configuration"
}
```

### JWT Token提取日志
```
🍪 Found cookies: [session, locale] for /api/admin/documents
🔑 Valid JWT token extracted for /api/admin/documents (eyJhbGciOiJIUzI1...)
```

### 连接错误日志
```
🔌 Connection Refused [ECONNREFUSED] - GET /api/admin/documents {
  "message": "connect ECONNREFUSED 127.0.0.1:6443",
  "suggestion": "Backend service is not running or not accessible",
  "backendUrl": "http://localhost:6443"
}
```

## 🛠️ 配置选项

### 环境变量
- `LOG_LEVEL=debug` - 启用详细日志模式
- `BFF_VERBOSE_LOGGING=true` - 强制启用详细日志
- `NODE_ENV=development` - 开发环境自动启用详细日志

### 详细日志模式
启用后会包含：
- 响应数据预览（限制500字符）
- 完整的请求头信息
- 请求体数据（敏感信息已过滤）

## 🔍 调试指南

### 常见问题排查

#### 1. 401 认证错误
**日志特征**: 🔐 Authentication Error
**检查项目**:
- JWT token是否存在于session cookie中
- Token是否已过期
- Token格式是否正确（3个部分用.分隔）

#### 2. 403 授权错误  
**日志特征**: 🚫 Authorization Error
**检查项目**:
- 用户是否有相应的权限
- 角色配置是否正确
- 权限检查逻辑是否有问题

#### 3. 连接问题
**日志特征**: 🔌 Connection Refused
**检查项目**:
- 后端服务是否正在运行
- 端口配置是否正确
- 网络连接是否正常

#### 4. 超时问题
**日志特征**: ⏰ Network Error
**检查项目**:
- 网络连接稳定性
- 后端服务响应时间
- 超时配置是否合理

## 📈 监控建议

### 1. 关键指标监控
- 401/403错误率趋势
- 平均响应时间
- 连接失败率
- 大文件上传成功率

### 2. 告警设置
- 401错误率 > 10% 时告警
- 平均响应时间 > 5秒时告警
- 连接失败率 > 5% 时告警

### 3. 日志聚合
建议使用ELK Stack或类似工具：
- 按requestId聚合完整请求链路
- 按错误类型统计问题分布
- 按用户IP分析访问模式

## 🧪 测试工具

运行测试脚本验证日志功能：
```bash
cd web-admin
node test_enhanced_logging.js
```

该脚本会测试各种错误场景并生成相应的日志输出。

## 📝 最佳实践

1. **开发环境**: 启用详细日志模式便于调试
2. **生产环境**: 使用简洁日志模式减少日志量
3. **敏感信息**: 确保不在日志中暴露完整的认证信息
4. **性能考虑**: 高频请求（如i18n、menu）自动跳过详细日志
5. **错误处理**: 根据日志建议快速定位和解决问题

## 🔄 持续改进

日志系统会根据实际使用情况持续优化：
- 添加更多错误类型的特定处理
- 优化日志格式和内容
- 增加性能监控指标
- 改进敏感信息过滤规则