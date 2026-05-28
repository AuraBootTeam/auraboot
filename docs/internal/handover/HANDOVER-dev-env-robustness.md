# HANDOVER — Dev 环境健壮性修复

## 背景

2026-04-15/16 workflow-demo 开发中发现 3 个 dev 环境问题，不影响功能但新开发者 onboard 一定会踩。

## 问题清单

### 1. DB 用户不一致（P1）

- `scripts/reset-db.sh` 用 `DB_USER="ghj"`（操作系统用户名）
- `application.yml` 用 `DATABASE_USERNAME:auraboot`（不存在的 role）
- 导致 `oss-reset-and-init.sh` 后 bootRun 报 `FATAL: role "auraboot" does not exist`
- **临时修复**：手动 `CREATE ROLE auraboot WITH LOGIN SUPERUSER`
- **正式修复建议**：`application.yml` 改 default 为 `${DATABASE_USERNAME:#{systemProperties['user.name']}}` 或 `reset-db.sh` 创建 `auraboot` role

### 2. ImEventListener NPE（P2）

```
ImEventListener: Failed to deliver IM notification for command=wd:create_leave_request
Cannot invoke "Object.equals(Object)" because "o" is null
```

- 每次 create_leave_request 命令触发
- `ImEventListener` 可能在检查 `model.getImConfig()` 或类似时 null 判断缺失
- **位置**：`platform/src/main/java/com/auraboot/framework/im/service/ImEventListener.java`
- **修复**：加 null guard

### 3. OTEL exporter 连接失败（P3）

```
Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4318
```

- Dev 环境没有 OTEL collector，每次 span 都报连接拒绝
- 无功能影响但日志污染严重
- **修复**：`application-dev.yml` 里关闭 OTEL exporter 或改为 `logging` exporter
