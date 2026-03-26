
# 二、在 PostgreSQL 里实现“code + version”的多版本配置存储

目标：对某个配置 **code**（可再细分 tenant、env、namespace），同时保存多个版本（草稿、已发布、历史），支持**读最新**、**读指定版本**、**对比/回滚**、**并发控制**、**留存与审计**。

## 2.1 设计要点

1. **版本号策略**
    * 简单自增 `version INT`（每个 `code` 独立递增，易排序、易并发控制）。
    *  `semver TEXT`（如 `1.2.3`）
2. **内容格式**：`JSONB` 存配置体，灵活可演进；可配合 JSON Schema 校验。
3. **状态机**：`DRAFT` / `PUBLISHED` / `DEPRECATED` / `ARCHIVED`。同一个 `code` 只能有**一个** `PUBLISHED` 的“当前生效”版本。
4. **并发控制**：乐观锁（`row_version` 或基于 `(code, next_version)` 的唯一约束），或在“发布”时检查最大版本。
5. **检索性能**：
    * `(tenant_id, namespace, code, version)` 复合唯一。
    * `(tenant_id, namespace, code) WHERE is_current` 部分索引，快速读当前。
6. **审计与可回滚**：所有版本保留；回滚=复制旧版本内容生成一个新版本并标记为 `PUBLISHED`。
7. **多租户/多环境**：把 `tenant_id`、`env`、`namespace` 纳入主键前缀，避免跨租户污染。
8. **灰度/生效区间（可选）**：`effective_from/ effective_to` 支持定时生效/下线。

## 2.2 表结构（推荐）

```sql
-- 1) 版本主表：每次变更一行
CREATE TABLE cfg_versions (
  id             BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT NOT NULL,              -- 多租户
  namespace      TEXT   NOT NULL DEFAULT 'default',
  env            TEXT   NOT NULL DEFAULT 'prod', -- dev/staging/prod
  code            TEXT   NOT NULL,                -- 配置键
  version        INT    NOT NULL,                -- 该key下递增版本
  semver         TEXT,                           -- 可选: "1.2.3"
  is_current     BOOLEAN NOT NULL DEFAULT FALSE, -- 当前发布版本的唯一标记
  status         TEXT   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','DELETED')),
  content        JSONB  NOT NULL,                -- 配置内容
  description    TEXT,
  created_by     TEXT   NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_by   TEXT,
  published_at   TIMESTAMPTZ,
  row_version    INT NOT NULL DEFAULT 1          -- 乐观锁
);

-- 唯一性：同一 (tenant/namespace/env/code/version) 唯一
CREATE UNIQUE INDEX ux_cfg_key_ver
  ON cfg_versions (tenant_id,   code, version);

-- 当前生效唯一：同一 (tenant/namespace/env/code) 下最多一个 is_current=true
CREATE UNIQUE INDEX ux_cfg_current_one
  ON cfg_versions (tenant_id,   code)
  WHERE is_current;

-- 查找当前或历史
CREATE INDEX ix_cfg_lookup
  ON cfg_versions (tenant_id,   code, version DESC);

-- 常用筛选
CREATE INDEX ix_cfg_status ON cfg_versions (status);
-- 可选：按内容字段建立GIN索引（示例：content里常查的path）
CREATE INDEX ix_cfg_content_gin ON cfg_versions USING GIN (content);
```

> 说明：`is_current` 与 `status='PUBLISHED'` 一致性由应用/触发器保证（见下）。

## 2.3 自动递增版本号（每个 code 独立）
TODO ,需要讨论确认下方案

## 2.4 发布事务（确保“唯一当前版本”）

```sql
-- 假设要把 (tenant, ns, env, code, version=:v) 发布为当前
BEGIN;

-- 1) 清除原current
UPDATE cfg_versions
   SET is_current = FALSE, status = CASE WHEN status='PUBLISHED' THEN 'DEPRECATED' ELSE status END
 WHERE tenant_id=:t AND namespace=:n AND env=:e AND code=:k AND is_current = TRUE;

-- 2) 将目标版本设为当前/已发布，带行级保护（可附加 row_version 乐观锁）
UPDATE cfg_versions
   SET is_current   = TRUE,
       status       = 'PUBLISHED',
       published_by = :actor,
       published_at = now()
 WHERE tenant_id=:t AND namespace=:n AND env=:e AND code=:k AND version=:v;

COMMIT;
```

> 借助 `ux_cfg_current_one` 部分唯一索引，即使并发发布也会因唯一冲突回滚，应用可重试或提示冲突。

## 2.5 常用读取

* **读取当前生效**：

```sql
SELECT * FROM cfg_versions
 WHERE tenant_id=:t AND namespace=:n AND env=:e AND code=:k
   AND is_current = TRUE
   AND (effective_from IS NULL OR now() >= effective_from)
   AND (effective_to   IS NULL OR now() <  effective_to)
LIMIT 1;
```

* **读取指定版本**：

```sql
SELECT * FROM cfg_versions
 WHERE tenant_id=:t AND namespace=:n AND env=:e AND code=:k AND version=:v;
```

* **对比两版本差异（JSON Patch 由应用层生成）**：

```sql
SELECT v1.content AS left_json, v2.content AS right_json
  FROM cfg_versions v1
  JOIN cfg_versions v2
    ON v1.tenant_id=v2.tenant_id AND v1.namespace=v2.namespace
   AND v1.env=v2.env AND v1.code=v2.code
 WHERE v1.version=:a AND v2.version=:b
   AND v1.tenant_id=:t AND v1.namespace=:n AND v1.env=:e AND v1.code=:k;
```


## 2.7 乐观并发控制

* 在编辑/保存草稿时，读到 `row_version`；保存时 `WHERE row_version = :old`，更新时 `row_version = row_version + 1`。若 `UPDATE count=0` → 冲突。

```sql
UPDATE cfg_versions
   SET content = :jsonb,
       description = :desc,
       row_version = row_version + 1
 WHERE id = :id AND row_version = :expected;
```


## 2.9 审计/溯源（简化）

```sql
CREATE TABLE cfg_audits (
  id BIGSERIAL PRIMARY KEY,
  ts TIMESTAMPTZ NOT NULL DEFAULT now(),
  actor TEXT NOT NULL,
  tenant_id BIGINT NOT NULL,
  namespace TEXT NOT NULL,
  env TEXT NOT NULL,
  code TEXT NOT NULL,
  action TEXT NOT NULL,    -- CREATE_VERSION / UPDATE_DRAFT / PUBLISH / ROLLBACK / DELETE ...
  from_version INT,
  to_version   INT,
  meta JSONB
);
```

## 2.10 典型 API 流程（伪代码）

* **创建草稿**：

```
BEGIN
  ver := alloc_next_version(tenant, ns, env, code)  -- 见 2.3
  INSERT cfg_versions(..., version=ver, status='DRAFT', is_current=false, content=payload)
COMMIT
```

* **发布**：

```
BEGIN
  -- 可选：验证 JSON Schema、运行规则校验
  unset_current(tenant, ns, env, code)
  set_current(tenant, ns, env, code, version=ver)  -- 见 2.4
  INSERT cfg_audits(action='PUBLISH', from_version=old, to_version=ver, ...)
COMMIT
```

* **回滚**（把旧版复制成新版本并发布）：

```
old_json := SELECT content FROM cfg_versions WHERE ... version=:old;
new_ver := alloc_next_version(...)
INSERT cfg_versions(..., version=new_ver, content=old_json, status='DRAFT');
PUBLISH(new_ver)
```