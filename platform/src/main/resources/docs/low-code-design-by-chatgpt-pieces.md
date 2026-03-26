
# 二、在 PostgreSQL 里实现“code + version”的多版本配置存储

目标：对某个配置 **code**（可再细分 tenant、env、namespace），同时保存多个版本（草稿、已发布、历史），支持**读最新**、**读指定版本**、**对比/回滚**、**并发控制**、**留存与审计**。

## 2.1 设计要点

1. **版本号策略**

    * 简单自增 `version INT`（每个 `code` 独立递增，易排序、易并发控制）。
    * 或 `semver TEXT`（如 `1.2.3`），推荐同时保留 `version_int` 用于排序。
2. **内容格式**：`JSONB` 存配置体，灵活可演进；可配合 JSON Schema 校验。
3. **状态机**：`DRAFT` / `PUBLISHED` / `DEPRECATED` / `ARCHIVED`。同一个 `code` 只能有**一个** `PUBLISHED` 的“当前生效”版本。
4. **并发控制**：乐观锁（`row_version` 或基于 `(code, next_version)` 的唯一约束），或在“发布”时检查最大版本。
5. **检索性能**：

    * `(tenant_id, namespace, code, version)` 复合唯一。
    * `(tenant_id, namespace, code) WHERE is_current` 部分索引，快速读当前。
    * JSONB 需要的字段可建 GIN 索引。
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
  status         TEXT   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED','ARCHIVED')),
  content        JSONB  NOT NULL,                -- 配置内容
  description    TEXT,
  created_by     TEXT   NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_by   TEXT,
  published_at   TIMESTAMPTZ,
  effective_from TIMESTAMPTZ,                    -- 可选：定时生效
  effective_to   TIMESTAMPTZ,                    -- 可选：定时失效
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

方案 A：事务内查询最大版本 + 1（配合唯一约束防并发冲突）。

```sql
-- 在应用侧事务中：
-- 1) SELECT COALESCE(MAX(version),0)+1 AS next_ver FROM cfg_versions WHERE tenant_id=? AND namespace=? AND env=? AND code=? FOR UPDATE;
-- 2) INSERT INTO cfg_versions(..., version=next_ver, status='DRAFT', is_current=false, content=...);
```

方案 B：使用一张“计数器表”提高并发（避免扫描历史）。

```sql
CREATE TABLE cfg_counters (
  tenant_id BIGINT NOT NULL,
  namespace TEXT   NOT NULL,
  env       TEXT   NOT NULL,
  code       TEXT   NOT NULL,
  next_ver  INT    NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id,   code)
);

-- 申请新版本号（单条行级锁）
UPDATE cfg_counters
   SET next_ver = next_ver + 1
 WHERE tenant_id=? AND namespace=? AND env=? AND code=?
 RETURNING next_ver - 1 AS new_version;
-- 若无行，先插入 (next_ver=2) 并返回 1。
```

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

## 2.6 视图 & 触发器（可选增强）

* **视图：”当前配置“**（便于按表读）：

```sql
CREATE VIEW cfg_current AS
SELECT *
  FROM cfg_versions
 WHERE is_current = TRUE
   AND (effective_from IS NULL OR now() >= effective_from)
   AND (effective_to   IS NULL OR now() <  effective_to);
```

* **触发器：保持状态一致性**
  确保 `is_current=true` 的行必须 `status='PUBLISHED'`；反之亦然。

```sql
CREATE FUNCTION cfg_enforce_status() RETURNS trigger AS $$
BEGIN
  IF NEW.is_current AND NEW.status <> 'PUBLISHED' THEN
    NEW.status := 'PUBLISHED';
  END IF;
  RETURN NEW;
END$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cfg_enforce
BEFORE INSERT OR UPDATE ON cfg_versions
FOR EACH ROW EXECUTE FUNCTION cfg_enforce_status();
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

## 2.8 留存与清理策略

* **发布线**：`PUBLISHED` 永久保留；`DEPRECATED` 保留最近 N 个；`DRAFT` 超过 X 天未更新自动归档。
* 批任务：

```sql
DELETE FROM cfg_versions
 WHERE status='DRAFT' AND created_at < now() - INTERVAL '90 days';
```

（谨慎：生产常改为 `ARCHIVED` 标记 + 冷存，避免硬删。）

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

## 2.11 实战建议

* **JSON Schema**：在应用层对 `content` 做 schema 校验，保证跨版本兼容。
* **可灰度**：引入 `segment_id` 或 `audience` 字段（门店/设备/用户组），在相同 `code` 下并行多条“当前”记录，但用更细粒度唯一约束 `(tenant,ns,env,code,segment) WHERE is_current`。
* **多通道读**：强一致读走主库，批量只读/缓存可走从库/Redis；配置“当前版本”可在发布时把 `(tenant,ns,env,code)->version` 写一份轻量缓存以降延迟。
* **变更通知**：发布时写事件表或消息队列（Kafka/Redis Stream）→ 下游动态刷新。

下面给你一套**可落地**的“低代码数据字典 + 动态表单/查询/规则”整体方案。重点回答：字典字段如何被表单、查询、规则共用；**复制**与**引用**两种复用模式如何设计；以及**存储实体**与**动态表单数据存取/查询**的技术实现。

---

# 1. 目标与原则

* **单一事实源（SSOT）**：用数据字典（Data Dictionary）统一定义**实体**与**字段**的语义、类型、校验、显示、规则映射与索引策略。
* **“一处建模，多处复用”**：同一字段定义可用于**表单**、**查询构建器**、**规则引擎**、**报表**等。
* **复制 vs 引用**：既支持“克隆为独立字段”（复制），也支持“指向共享定义”（引用），并能安全地进行“版本升级”。
* **渐进式存储**：默认 JSONB 文档存储；热门字段**投影/物化**为列或宽表；提供索引/查询优化与迁移路径。

---

# 2. 核心数据模型（PostgreSQL）

## 2.1 命名空间与版本（多租户）

* `tenant_id`（多租户）、`namespace`（功能域）、`env`（环境：dev/stg/prod）
* 采用 `code + version` 的多版本机制（与之前讨论一致），**发布线**用 `is_current` 标识当前版本。

## 2.2 实体与字段（数据字典）

```sql
-- 字典实体（如：customer、order、device）
CREATE TABLE dict_entities (
  id            BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT   NOT NULL,
  namespace     TEXT     NOT NULL,   -- 例：crm.customer
  env           TEXT     NOT NULL DEFAULT 'prod',
  code           TEXT     NOT NULL,   -- 实体key（唯一域内）
  name          TEXT     NOT NULL,   -- 展示名
  description   TEXT,
  version       INT      NOT NULL,   -- 内部递增版本
  semver        TEXT,                -- 可选：语义版本
  is_current    BOOLEAN  NOT NULL DEFAULT FALSE,
  status        TEXT     NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PUBLISHED','DEPRECATED','ARCHIVED')),
  ui_meta       JSONB    NOT NULL DEFAULT '{}'::jsonb,   -- 列表/详情的UI建议
  model_meta    JSONB    NOT NULL DEFAULT '{}'::jsonb,   -- 业务元信息（聚合根、软删、审计等）
  created_by    TEXT     NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  row_version   INT      NOT NULL DEFAULT 1
);

CREATE UNIQUE INDEX ux_ent_key_ver
  ON dict_entities(tenant_id,   code, version);

CREATE UNIQUE INDEX ux_ent_current
  ON dict_entities(tenant_id,   code) WHERE is_current;
```

```sql
-- 字典字段（属于某实体的一个字段定义）
CREATE TABLE dict_fields (
  id             BIGSERIAL PRIMARY KEY,
  tenant_id      BIGINT   NOT NULL,
  namespace      TEXT     NOT NULL,
  env            TEXT     NOT NULL DEFAULT 'prod',
  entity_key     TEXT     NOT NULL,       -- 归属实体key
  entity_version INT      NOT NULL,       -- 对应实体版本（冻结引用）
  field_key      TEXT     NOT NULL,       -- 字段key（例：phone, age）
  name           TEXT     NOT NULL,       -- 字段展示名
  data_type      TEXT     NOT NULL,       -- string/int/decimal/bool/date/datetime/json/enum/ref/computed
  required       BOOLEAN  NOT NULL DEFAULT FALSE,
  unique_in_entity BOOLEAN NOT NULL DEFAULT FALSE,
  unit           TEXT,                     -- 单位：kg、cm、CNY...
  precision      INT,                      -- decimal精度
  scale          INT,                      -- decimal小数位
  min_value      TEXT,                     -- 下界（按类型解释）
  max_value      TEXT,                     -- 上界
  regex          TEXT,                     -- 正则校验
  default_value  JSONB,                    -- 默认值
  enum_set_id    BIGINT,                   -- 若为枚举，指向选项集
  ref_target     JSONB,                    -- 若为ref：{namespace,code,version?, cardinality:'one|many', fk:'id'}
  compute_expr   TEXT,                     -- 若为computed：DSL/表达式（禁止副作用）
  pii_class      TEXT,                     -- PII分级：NONE/BASIC/SENSITIVE
  index_hint     JSONB DEFAULT '{}'::jsonb,-- {btree:true, gin:true, expr:[...] }
  ui_schema      JSONB DEFAULT '{}'::jsonb,-- 表单控件/占位/可见性条件等
  query_schema   JSONB DEFAULT '{}'::jsonb,-- 查询构件（操作符、提示等）
  rule_schema    JSONB DEFAULT '{}'::jsonb,-- 规则引擎映射名/转换
  status         TEXT   NOT NULL DEFAULT 'DRAFT',
  version        INT    NOT NULL,          -- 字段自身版本（独立演化）
  semver         TEXT,
  is_current     BOOLEAN NOT NULL DEFAULT FALSE,
  description    TEXT,
  created_by     TEXT   NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  row_version    INT    NOT NULL DEFAULT 1
);

-- 一个实体版本下，字段key唯一
CREATE UNIQUE INDEX ux_field_key_ver
  ON dict_fields(tenant_id,   entity_key, entity_version, field_key, version);

-- 当前字段唯一
CREATE UNIQUE INDEX ux_field_current
  ON dict_fields(tenant_id,   entity_key, entity_version, field_key) WHERE is_current;
```

### 枚举集合

```sql
CREATE TABLE dict_enums (
  id          BIGSERIAL PRIMARY KEY,
  tenant_id   BIGINT NOT NULL,
  namespace   TEXT   NOT NULL,
  env         TEXT   NOT NULL DEFAULT 'prod',
  code         TEXT   NOT NULL,        -- 例：gender, status_type
  name        TEXT   NOT NULL,
  version     INT    NOT NULL,
  semver      TEXT,
  is_current  BOOLEAN NOT NULL DEFAULT FALSE,
  items       JSONB  NOT NULL,        -- [{code:'M',label:'男'}, ...] 可多语言
  created_by  TEXT   NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_enum_key_ver
  ON dict_enums(tenant_id,   code, version);

CREATE UNIQUE INDEX ux_enum_current
  ON dict_enums(tenant_id,   code) WHERE is_current;
```

---

# 3. 复用模型：**复制** vs **引用**

## 3.1 定义

* **复制（Clone）**：把字段定义**拷贝**到目标实体/表单中，生成**新字段ID**；后续彼此独立演化。适合需要**局部改动**（名称、校验、UI）的场景。
* **引用（Reference）**：指向已有字段定义（或字段库模板），通过**指针 + 版本策略**共享。可配三种指针：

    1. **锁定版本（pinned）**：固定到 `semver=1.2.0` 或 `version=17`；
    2. **跟随主干（floating latest）**：总是用对方 `is_current`；
    3. **有条件升级（range）**：允许 `~1.2`、`^1.2` 等 semver 范围，升级需审核。

## 3.2 关系表

```sql
-- 复用映射（表单字段或实体字段对“来源字段”的引用）
CREATE TABLE dict_field_links (
  id               BIGSERIAL PRIMARY KEY,
  tenant_id        BIGINT NOT NULL,
  consumer_scope   TEXT   NOT NULL,          -- 'entity' | 'form' | 'query_preset' ...
  consumer_key     TEXT   NOT NULL,          -- 目标实体key或表单key
  consumer_version INT    NOT NULL,
  field_key        TEXT   NOT NULL,          -- 本地字段key（可与来源同名或别名）
  link_mode        TEXT   NOT NULL CHECK (link_mode IN ('CLONE','REF_PIN','REF_FLOAT','REF_RANGE')),
  source_namespace TEXT   NOT NULL,
  source_entity_key TEXT  NOT NULL,
  source_field_key TEXT   NOT NULL,
  source_version   INT,                      -- REF_PIN 使用
  semver_range     TEXT,                     -- REF_RANGE 使用（如 ^1.2）
  overlay_meta     JSONB DEFAULT '{}'::jsonb,-- 允许有限覆盖（仅UI、占位、help，不改语义）
  created_by       TEXT   NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**升级策略**

* `REF_FLOAT`：发布时同步拉取上游最新 `is_current`；若上游破坏性变更，需要阻断并出告警。
* `REF_RANGE`：发布前**求解**可用的最高 semver 版本，若包含破坏性变更，需人工确认。
* `CLONE`：无升级关系，完全独立。

---

# 4. 表单、查询、规则的统一绑定

## 4.1 表单定义（低代码）

```sql
CREATE TABLE form_defs (
  id           BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  namespace    TEXT   NOT NULL,
  env          TEXT   NOT NULL DEFAULT 'prod',
  code          TEXT   NOT NULL,           -- 表单key
  name         TEXT   NOT NULL,
  entity_key   TEXT   NOT NULL,           -- 绑定的实体（用于落库与权限）
  entity_version INT  NOT NULL,
  version      INT    NOT NULL,
  semver       TEXT,
  is_current   BOOLEAN NOT NULL DEFAULT FALSE,
  layout       JSONB  NOT NULL,           -- 表单布局：区、列、tab、可见性条件
  widgets      JSONB  NOT NULL,           -- 字段控件数组：{field_key, widget, props, rules...}
  actions      JSONB  NOT NULL DEFAULT '[]'::jsonb, -- 提交/草稿/审批流
  status       TEXT   NOT NULL DEFAULT 'DRAFT',
  created_by   TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_form_key_ver
  ON form_defs(tenant_id,   code, version);

CREATE UNIQUE INDEX ux_form_current
  ON form_defs(tenant_id,   code) WHERE is_current;
```

* `widgets[].field_key` 可直接使用 **字典字段** 或 `dict_field_links` 中的映射（复制/引用）。
* **可见性/校验规则** 写入 `widgets[].rules`，可引用字段路径：`${field('customer.age')}`。

## 4.2 查询构建器（Query Preset）

```sql
CREATE TABLE query_presets (
  id           BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  namespace    TEXT   NOT NULL,
  env          TEXT   NOT NULL DEFAULT 'prod',
  code          TEXT   NOT NULL,
  name         TEXT   NOT NULL,
  entity_key   TEXT   NOT NULL,
  entity_version INT  NOT NULL,
  version      INT    NOT NULL,
  filters      JSONB  NOT NULL,  -- [{field_key, op, value, logic}, ...]
  selects      JSONB  NOT NULL,  -- 选择字段列表/表达式
  sorts        JSONB  NOT NULL,  -- 排序定义
  limit        INT,
  status       TEXT   NOT NULL DEFAULT 'DRAFT',
  is_current   BOOLEAN NOT NULL DEFAULT FALSE,
  created_by   TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

* 过滤、选择、排序都使用**字典字段**类型信息推导 UI/SQL/索引。

## 4.3 规则引擎绑定（Rule Binding）

```sql
CREATE TABLE rule_defs (
  id           BIGSERIAL PRIMARY KEY,
  tenant_id    BIGINT NOT NULL,
  namespace    TEXT   NOT NULL,
  env          TEXT   NOT NULL DEFAULT 'prod',
  code          TEXT   NOT NULL,
  name         TEXT   NOT NULL,
  entity_key   TEXT   NOT NULL,
  entity_version INT  NOT NULL,
  version      INT    NOT NULL,
  dsl          TEXT   NOT NULL,            -- 规则DSL（如：when ... then ...）
  bindings     JSONB  NOT NULL,            -- { "vars": [{name, field_key, transform?}] }
  is_current   BOOLEAN NOT NULL DEFAULT FALSE,
  status       TEXT   NOT NULL DEFAULT 'DRAFT',
  created_by   TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

* `bindings.vars[].field_key` 指向字典字段；引擎执行前完成**类型转换**与**单位归一**。

---

# 5. 动态表单的数据存储与查询

## 5.1 基础形态：JSONB 文档存储（最快上线）

```sql
-- 实体实例（通用记录表，适配任意实体/表单版本）
CREATE TABLE entity_records (
  id            BIGSERIAL PRIMARY KEY,
  tenant_id     BIGINT   NOT NULL,
  namespace     TEXT     NOT NULL,
  env           TEXT     NOT NULL DEFAULT 'prod',
  entity_key    TEXT     NOT NULL,
  entity_version INT     NOT NULL,              -- 记录采用的实体版本
  form_key      TEXT,                           -- 来源表单（可空）
  form_version  INT,
  data          JSONB    NOT NULL,              -- 真正的表单/实体数据
  status        TEXT     NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/DELETED/DRAFT
  created_by    TEXT     NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by    TEXT,
  updated_at    TIMESTAMPTZ
);

-- 常用索引
CREATE INDEX ix_rec_entity ON entity_records(tenant_id,   entity_key);
CREATE INDEX ix_rec_gin ON entity_records USING GIN (data jsonb_path_ops);
```

### 典型查询

* 按字段值过滤（Postgres 12+ 支持 jsonpath）：

```sql
-- 年龄 >= 18
SELECT * FROM entity_records
 WHERE tenant_id=:t AND namespace='crm.customer' AND entity_key='customer'
   AND data @? '$.age ? (@ >= 18)';
```

* 传统 JSON 操作符（表达式索引可优化）：

```sql
-- 手机号等于 '138...'
SELECT * FROM entity_records
 WHERE tenant_id=:t AND entity_key='customer'
   AND data->>'phone' = '13800000000';

-- 为提高性能，建表达式索引：
CREATE INDEX ix_rec_phone ON entity_records
  ((data->>'phone'));
```

## 5.2 渐进式优化：**投影/物化/宽表**

* 为热点字段建立**生成列**或**投影表**，兼顾查询性能与灵活性：

```sql
-- 生成列（Postgres 12+）
ALTER TABLE entity_records
ADD COLUMN phone TEXT
GENERATED ALWAYS AS ((data->>'phone')) STORED;

CREATE INDEX ix_rec_phone_col ON entity_records(phone);
```

* **投影表**（每个实体一个“窄宽表”）：

```sql
CREATE TABLE entity_projection_customer (
  record_id  BIGINT PRIMARY KEY REFERENCES entity_records(id) ON DELETE CASCADE,
  name       TEXT,
  phone      TEXT,
  age        INT,
  gender     TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 通过触发器在 entity_records 变更时同步投影
```

* **物化视图**（报表）：

```sql
CREATE MATERIALIZED VIEW mv_customer_basic AS
SELECT id, data->>'name' AS name, (data->>'age')::int AS age
FROM entity_records
WHERE entity_key='customer';

CREATE INDEX ix_mv_customer_age ON mv_customer_basic(age);
```

> 策略：默认 JSONB，全量灵活；**热点**字段被**索引/投影/物化**，以满足高频查询和排序统计。

## 5.3 数据校验与约束

* **写入前**（BFF 或后端服务）：基于字典字段的 `data_type/regex/min/max/enum/ref` 做 JSON Schema 校验。
* **数据库层**（可选）：触发器二次校验关键字段；对生成列加 `CHECK` 约束（如 age >= 0）。
* **引用完整性**：若 `ref_target` 指向另一实体，可在存储时校验目标 `record_id` 是否存在（软外键）。

---

# 6. 复制/引用在表单和查询中的表现

* 表单字段使用 `dict_field_links`：

    * `CLONE`：落地为**本地字段定义**，`ui_schema/query_schema` 可自由调整；
    * `REF_*`：读取“来源字段”的类型与校验；UI可用 `overlay_meta` 做**外观覆盖**（如占位符、宽度），不允许更改语义（类型/单位/约束）。
* 查询构建器读取字段的 `query_schema` 自动提供**操作符集合**（=、in、between、contains、within box 等）。
* 规则绑定读取 `rule_schema`（如转换：金额单位从“分”转“元”，或时间统一为 UTC）。

---

# 7. 版本与兼容性治理

* **实体/字段**：采用 `DRAFT → PUBLISHED → DEPRECATED` 生命周期；`is_current` 唯一。
* **引用升级**：

    * `REF_PIN`：只有显式选择目标版本才升级；
    * `REF_FLOAT`：发布时自动拉最新，若检测到**破坏性变更**（类型/必填/范围变化），阻断并提示；
    * `REF_RANGE`：在可接受范围内自动选最新，越界则需人工确认。
* **破坏性变更检测**：在发布前比对**旧/新字段定义**生成 Diff，标注风险（例如 string→int、min/max 收紧、枚举删除项）。

---

# 8. API 形态（示例）

* **字典**

    * `POST /dict/entities/{entityCode}/versions`（创建草稿）
    * `POST /dict/entities/{entityCode}/publish?version=...`（发布为当前）
    * `GET /dict/entities/{entityCode}?current=true`
    * `POST /dict/entities/{entityCode}/fields/{code}/links`（建立 CLONE/REF 链接）
* **表单**

    * `POST /forms/{blockCode}/versions`、`POST /forms/{blockCode}/publish`
    * `GET /forms/{blockCode}?current=true`
* **数据**

    * `POST /records/{entityCode}`（提交/草稿）
    * `GET /records/{entityCode}`（通用查询：filters/selects/sorts，基于 query preset 或 ad-hoc）
    * `POST /records/{entityCode}/{id}/_validate`（只校验）
* **规则**

    * `POST /rules/{ruleKey}/evaluate`（传入 record/上下文，返回命中与动作）

---

# 9. 权限与审计

* **权限**：按 `namespace/entity/field` 三级授权（读/写/发布/架构变更）。
* **审计**：所有架构与数据变更写 `audit_log`：谁在什么时候对哪个租户/命名空间做了什么（创建字段、发布、升级引用、写入数据等）。

---

# 10. 扩展讨论（实践建议）

1. **字段库（Field Library）**

    * 抽象出跨实体可复用的通用字段模板（手机号、身份证、邮箱、金额、地址、经纬度等）。
    * 建议只允许**UI层覆盖**，禁止更改语义，避免“同名异义”。

2. **单位与度量（UOM）**

    * 在字段定义中标注 `unit`，规则/查询可自动换算（如 cm ↔ m）。
    * 统一存储单位，UI/报表做显示转换。

3. **数据质量治理**

    * 为关键字段标记 `quality_rules`（唯一性、外部校验API、黑名单等）；
    * 周期性扫描生成 DQ 报表。

4. **国际化**

    * `name/label/help` 支持 i18n：将 `ui_schema.labels` 设计成 `{ "zh-CN":"姓名","en-US":"Name" }`。

5. **性能**

    * JSONB 路径索引只建在高频字段；
    * 复杂报表走物化视图 + 刷新；
    * 高频过滤建**表达式索引**或**投影列**；
    * 大体量冷热分层（活跃 90 天内在主表，历史归档表）。

6. **灰度与多态**

    * 在 `entity_records` 增加 `segment_id` 或 `audience`，与规则结合实现灰度字段/表单（同实体针对不同门店/设备展示不同字段）。

---

# 11. 小型端到端示例（片段）

* 定义 `crm.customer` 实体（v3），字段 `phone`（string，必填，正则），`age`（int，>=0），`gender`（enum: gender）
* 表单 `customer.create`（v5）引用 `phone`（REF\_PIN=1.1.0），拷贝 `gender`（CLONE，并把 UI 控件改成分组按钮）
* 提交数据：

```json
{
  "phone": "13800000000",
  "age": 26,
  "gender": "M"
}
```

* 查询：`age >= 18 AND gender IN ('M','F')` 走 JSONB 路径 + age 投影列索引。
* 规则：当 `age < 18` 禁止提交；当 `phone` 命中黑库触发人工审核。

---

## 总结

* 用**数据字典**统一实体/字段语义，面向**表单/查询/规则**一次建模、全域复用。
* 通过 `dict_field_links` 同时支持**复制（CLONE）**与**引用（REF\_PIN/REF\_FLOAT/REF\_RANGE）**，并提供**升级/兼容**治理。
* 动态数据采用 **JSONB 文档存储 + 表达式/路径索引** 起步，按需向**投影列/物化视图/宽表**渐进演化，既灵活又有性能上限。


可以的，而且是它的强项。下面把“增删改查 + 列表查询”的落地方案、SQL 例子与性能要点一次给全，你可以直接照抄改名使用。

# 1. CRUD：如何落地

## 1) Create（新增）

* 前端表单 → 后端按字典校验 → 写入 `entity_records.data(JSONB)`，同时回填必要的**投影列**（如 `phone/age/updated_at`）。

```sql
INSERT INTO entity_records
(tenant_id,   entity_key, entity_version, form_key, form_version, data, status, created_by)
VALUES (:t, 'crm.customer', 'prod', 'customer', :ent_ver, 'customer.create', :form_ver, :jsonb, 'ACTIVE', :user);
```

## 2) Read（详情）

```sql
SELECT * FROM entity_records
 WHERE tenant_id=:t AND namespace='crm.customer' AND entity_key='customer'
   AND id=:id AND status='ACTIVE';
```

## 3) Update（编辑/部分字段）

* 乐观锁：带 `row_version`（或用 `updated_at`/ETag）；
* 局部更新：`jsonb_set` + 同步投影列。

```sql
UPDATE entity_records
   SET data = jsonb_set(data, '{phone}', to_jsonb(:phone::text), true),
       updated_by=:user, updated_at=now()
 WHERE id=:id AND tenant_id=:t AND status='ACTIVE';
```

## 4) Delete（删除）

* 建议**软删**：

```sql
UPDATE entity_records
   SET status='DELETED', updated_by=:user, updated_at=now()
 WHERE id=:id AND tenant_id=:t;
```

---

# 2. 列表查询（分页/筛选/排序/搜索）

## 2.1 通用查询协议（BFF → 服务）

```json
{
  "entity":"crm.customer",
  "selects":["id","data.name","data.phone","data.age","created_at","updated_at"],
  "filters":[
    {"field":"data.age","op":">=","value":18},
    {"field":"data.gender","op":"in","value":["M","F"]},
    {"field":"data.phone","op":"like","value":"138%"}
  ],
  "sort":[{"field":"updated_at","dir":"desc"}],
  "page":{"size":20,"cursor":"1700000000,84521"}  // 可选：keyset 游标
}
```

## 2.2 SQL 编译（示例：Offset & Keyset 两种分页）

### Offset 分页（简单好用）

```sql
SELECT id, data->>'name' AS name, data->>'phone' AS phone,
       (data->>'age')::int AS age, created_at, updated_at
FROM entity_records
WHERE tenant_id=:t AND namespace='crm.customer' AND entity_key='customer'
  AND status='ACTIVE'
  AND (data->>'gender') IN ('M','F')
  AND (data->>'phone') LIKE '138%'
  AND (data->>'age')::int >= 18
ORDER BY updated_at DESC, id DESC
LIMIT :size OFFSET :offset;

SELECT COUNT(*) ...  -- 统计总数（可选）
```

### Keyset 分页（大数据量推荐）

* 游标 = 上一页最后一行的 `(updated_at,id)`。

```sql
SELECT id, data->>'name' AS name, data->>'phone' AS phone,
       (data->>'age')::int AS age, created_at, updated_at
FROM entity_records
WHERE tenant_id=:t AND namespace='crm.customer' AND entity_key='customer'
  AND status='ACTIVE'
  AND (data->>'gender') IN ('M','F')
  AND (data->>'phone') LIKE '138%'
  AND (data->>'age')::int >= 18
  AND (updated_at, id) < (:cursor_updated_at, :cursor_id)
ORDER BY updated_at DESC, id DESC
LIMIT :size;
```

## 2.3 索引与性能要点（开箱即用）

* 主筛选：

```sql
CREATE INDEX ix_rec_entity ON entity_records(tenant_id,   entity_key, status);
CREATE INDEX ix_rec_updated ON entity_records(updated_at DESC, id DESC);
```

* JSONB 高频字段：**表达式索引**或**生成列**

```sql
-- 表达式索引（等值/前缀匹配）
CREATE INDEX ix_rec_phone_expr ON entity_records ((data->>'phone'));
CREATE INDEX ix_rec_gender_expr ON entity_records ((data->>'gender'));
CREATE INDEX ix_rec_age_expr    ON entity_records (((data->>'age')::int));

-- 或生成列（Postgres 12+）
ALTER TABLE entity_records
  ADD COLUMN phone TEXT GENERATED ALWAYS AS ((data->>'phone')) STORED,
  ADD COLUMN age   INT  GENERATED ALWAYS AS (((data->>'age')::int)) STORED;
CREATE INDEX ix_rec_phone_col ON entity_records(phone);
CREATE INDEX ix_rec_age_col   ON entity_records(age);
```

* 复杂嵌套/数组条件：加 GIN

```sql
CREATE INDEX ix_rec_gin ON entity_records USING GIN (data jsonb_path_ops);
```

## 2.4 全文检索（可选）

* 合并若干字段为 `tsvector`，支持关键字搜索：

```sql
ALTER TABLE entity_records
  ADD COLUMN fts tsvector
  GENERATED ALWAYS AS (to_tsvector('simple',
    coalesce(data->>'name','') || ' ' || coalesce(data->>'phone',''))) STORED;

CREATE INDEX ix_rec_fts ON entity_records USING GIN (fts);

-- 查询
SELECT id, ... FROM entity_records
WHERE tenant_id=:t AND entity_key='customer' AND status='ACTIVE'
  AND fts @@ plainto_tsquery(:q);
```

---

# 3. 查询构建器：基于字典自动“控件 ↔ SQL”映射

* 按 `dict_fields.data_type/query_schema` 自动决定**操作符**与**输入控件**：

    * `string`：`=、like、ilike、in、is_null`
    * `int/decimal`：`=、>、>=、<、<=、between、in`
    * `date/datetime`：`between（含时区）`
    * `enum`：`in`
    * `ref`：`in`（对方实体的 id 列表）或 label 关键词（走对方投影/fts）
* BFF 只把**用户过滤器**映射成安全的**参数化 SQL**；白名单字段来自 `dict_fields`，避免 SQL 注入。

---

# 4. 引用字段与关联展示

* `ref` 字段（例如 `customer.city_id` 引用 `geo.city`）两种做法：

    1. **冗余 label**：在 `data` 或投影列里同时写入 `city_name`，列表渲染不需要 join（最快）。
    2. **轻量 join**：给被引用实体做**投影表**（`entity_projection_city`），只含 `id,name`：

  ```sql
  SELECT r.id, r.data->>'name' AS name, c.name AS city_name
  FROM entity_records r
  LEFT JOIN entity_projection_city c ON c.record_id = (r.data->>'city_id')::bigint
  WHERE ...
  ```

    * 建议：写入时即冗余 `label`，以换取查询快；一致性通过触发器或后台批任务修正。

---

# 5. 列表的“可视化 Schema”与复用

* 通过 `query_presets` 保存列表配置（列头、默认筛选、排序、页大小），版本化发布；页面直接加载 preset 渲染。
* 支持**复制（CLONE）**与**引用（REF\_\*）** preset：门店 A/B 列表共用一套筛选逻辑，只改一两个字段显示即可。

---

# 6. 端到端接口（样例）

* `POST /records/{entity}/query` → 返回 `items[] + nextCursor + total(optional)`
* `POST /records/{entity}`（新增）
* `PATCH /records/{entity}/{id}`（部分更新）
* `DELETE /records/{entity}/{id}`（软删）
* 所有写操作都用 `row_version/updated_at` 做**乐观锁**；读列表时加 `If-None-Match` 走缓存（可选）。

---

# 7. 结论（能不能“很好地支持”？）

**可以，而且很稳。**

* **灵活**：JSONB + 字典驱动，字段随表单/规则演化；
* **高性能**：热点字段投影/表达式/GIN 索引 + Keyset 分页；
* **可治理**：查询预设、版本发布、复制/引用升级策略；
* **安全**：白名单字段 + 参数化 SQL + 乐观锁 + 审计。

太好了，用 **Order** 来跑一遍端到端。下面给你一套可直接落地的定义、索引、典型查询（含 Keyset 分页）、以及 API 载荷示例。默认：

* `namespace='oms.order'`
* 数据落在 `entity_records`（JSONB）+ 若干**投影列/投影表**以提速
* 多租户、软删、乐观锁思路沿用之前方案

---

# 1) 字典定义（简化示例）

**实体**：`order`（v3）
**字段**（节选，类型/用途→驱动表单/查询/规则）：

* `order_no:string`（唯一，可搜索，like）
* `customer_id:ref`（指向 `crm.customer`）
* `store_id:ref`（门店）
* `status:enum`（`CREATED/PAID/ALLOCATED/SHIPPED/CLOSED/REFUNDED`）
* `channel:enum`（`ONLINE/OFFLINE/THIRD`）
* `currency:string(ISO)`，`total_amount_cents:int`（统一单位“分”）
* `paid_at:datetime?`，`created_at:datetime`（系统生成）
* `items:json[]`（skuId, qty, priceCents, name）
* `shipping:json`（receiver, phone, address, regionCodes）
* `remarks:string?`

> 表单/查询允许：按 `status/channel/store_id/date range/金额范围/手机号前缀/订单号` 等筛选。

---

# 2) 存储结构

## 2.1 主表（JSONB + 投影列）

```sql
CREATE TABLE entity_records (
  id              BIGSERIAL PRIMARY KEY,
  tenant_id       BIGINT   NOT NULL,
  namespace       TEXT     NOT NULL,
  env             TEXT     NOT NULL DEFAULT 'prod',
  entity_key      TEXT     NOT NULL,      -- 'order'
  entity_version  INT      NOT NULL,
  data            JSONB    NOT NULL,      -- 订单JSON
  status          TEXT     NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/DELETED
  created_by      TEXT     NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_by      TEXT,
  updated_at      TIMESTAMPTZ
);

CREATE INDEX ix_rec_entity         ON entity_records(tenant_id,   entity_key, status);
CREATE INDEX ix_rec_updated        ON entity_records(updated_at DESC, id DESC);
CREATE INDEX ix_rec_data_gin       ON entity_records USING GIN (data jsonb_path_ops);
```

## 2.2 生成列（加速高频过滤/排序）

```sql
-- Postgres 12+ 生成列：从 JSONB 投影
ALTER TABLE entity_records
  ADD COLUMN order_no           TEXT GENERATED ALWAYS AS ((data->>'order_no')) STORED,
  ADD COLUMN customer_id        BIGINT GENERATED ALWAYS AS (((data->>'customer_id')::bigint)) STORED,
  ADD COLUMN store_id           BIGINT GENERATED ALWAYS AS (((data->>'store_id')::bigint)) STORED,
  ADD COLUMN status_code        TEXT  GENERATED ALWAYS AS ((data->>'status')) STORED,
  ADD COLUMN channel_code       TEXT  GENERATED ALWAYS AS ((data->>'channel')) STORED,
  ADD COLUMN total_amount_cents INT   GENERATED ALWAYS AS (((data->>'total_amount_cents')::int)) STORED,
  ADD COLUMN created_time       TIMESTAMPTZ GENERATED ALWAYS AS ((data->>'created_at')::timestamptz) STORED,
  ADD COLUMN paid_time          TIMESTAMPTZ GENERATED ALWAYS AS (NULLIF(data->>'paid_at','')::timestamptz) STORED,
  ADD COLUMN receiver_phone     TEXT  GENERATED ALWAYS AS ((data #>> '{shipping,phone}')) STORED;

-- 索引（等值、前缀、范围、排序）
CREATE UNIQUE INDEX ux_order_no           ON entity_records(tenant_id, namespace, entity_key, order_no) WHERE status='ACTIVE';
CREATE INDEX ix_order_store_status_time   ON entity_records(tenant_id, namespace, entity_key, store_id, status_code, created_time DESC, id DESC) WHERE status='ACTIVE';
CREATE INDEX ix_order_paid_time           ON entity_records(tenant_id, namespace, entity_key, paid_time DESC, id DESC) WHERE status='ACTIVE';
CREATE INDEX ix_order_amount              ON entity_records(tenant_id, namespace, entity_key, total_amount_cents) WHERE status='ACTIVE';
CREATE INDEX ix_order_phone_expr          ON entity_records ((receiver_phone));
CREATE INDEX ix_order_status              ON entity_records(tenant_id, namespace, entity_key, status_code) WHERE status='ACTIVE';
```

> 这些索引覆盖了：按门店+状态+时间的列表、按支付时间排序、按金额区间、按手机号或订单号搜索等核心场景。

---

# 3) 写入/更新/删除（片段）

## Create

```sql
INSERT INTO entity_records
(tenant_id,   entity_key, entity_version, data, created_by)
VALUES (:t, 'oms.order', 'prod', 'order', :ent_ver, :jsonb, :user)
RETURNING id;
```

> 后端在入库前做**字典驱动校验**（必填、类型、枚举、金额单位等）。

## Update（部分字段）

```sql
UPDATE entity_records
   SET data = jsonb_set(
                 jsonb_set(data, '{status}', to_jsonb(:status::text), true),
                 '{paid_at}', to_jsonb(:paidAt::text), true
               ),
       updated_by=:user, updated_at=now()
 WHERE id=:id AND tenant_id=:t AND status='ACTIVE';
```

## Soft Delete

```sql
UPDATE entity_records
   SET status='DELETED', updated_by=:user, updated_at=now()
 WHERE id=:id AND tenant_id=:t;
```

---

# 4) 列表查询（典型用法）

## 4.1 普通 Offset 分页

> 「门店 1001、已支付或已发货、最近 30 天、金额 ≥ ¥100（=10000 分），按支付时间倒序」

```sql
SELECT id, order_no,
       customer_id, store_id, status_code, channel_code,
       (total_amount_cents/100.0) AS total_amount,
       created_time, paid_time
FROM entity_records
WHERE tenant_id=:t AND namespace='oms.order' AND entity_key='order' AND status='ACTIVE'
  AND store_id = 1001
  AND status_code IN ('PAID','SHIPPED')
  AND paid_time >= (now() - INTERVAL '30 days')
  AND total_amount_cents >= 10000
ORDER BY paid_time DESC NULLS LAST, id DESC
LIMIT :size OFFSET :offset;

-- 可选统计
-- SELECT COUNT(*) FROM ... (同WHERE条件)
```

## 4.2 Keyset 分页（高并发/大数据量）

> 游标 = 上一页最后一行的 `(paid_time,id)`

```sql
SELECT id, order_no, customer_id, store_id, status_code, channel_code,
       (total_amount_cents/100.0) AS total_amount, created_time, paid_time
FROM entity_records
WHERE tenant_id=:t AND namespace='oms.order' AND entity_key='order' AND status='ACTIVE'
  AND store_id = 1001
  AND status_code IN ('PAID','SHIPPED')
  AND paid_time >= (now() - INTERVAL '30 days')
  AND total_amount_cents >= 10000
  AND (paid_time, id) < (:cursor_paid_time, :cursor_id)   -- keyset 条件
ORDER BY paid_time DESC NULLS LAST, id DESC
LIMIT :size;
```

## 4.3 模糊搜索（订单号/手机号前缀）

```sql
-- 订单号前缀
SELECT id, order_no, total_amount_cents, status_code, created_time
FROM entity_records
WHERE tenant_id=:t AND namespace='oms.order' AND entity_key='order' AND status='ACTIVE'
  AND order_no LIKE :prefix || '%'
ORDER BY created_time DESC, id DESC
LIMIT 20;

-- 收件人手机号前缀
SELECT id, order_no, receiver_phone, status_code
FROM entity_records
WHERE tenant_id=:t AND namespace='oms.order' AND entity_key='order' AND status='ACTIVE'
  AND receiver_phone LIKE :phonePrefix || '%'
ORDER BY created_time DESC, id DESC
LIMIT 20;
```

## 4.4 组合筛选（未发货但已分配、按创建时间区间）

```sql
SELECT id, order_no, status_code, created_time
FROM entity_records
WHERE tenant_id=:t AND namespace='oms.order' AND entity_key='order' AND status='ACTIVE'
  AND status_code = 'ALLOCATED'
  AND created_time BETWEEN :begin AND :end
ORDER BY created_time DESC, id DESC
LIMIT :size OFFSET :offset;
```

---

# 5) 关联显示：冗余或轻量 Join

### 冗余 label（推荐）

* 写入时把 `customer_name`、`store_name` 同步冗余进 `data`（或生成列），列表直接用，最快。

### 轻量 Join（需要强一致）

* 维护一个投影表 `entity_projection_customer(id,name,phone)`：

```sql
SELECT o.id, o.order_no, o.status_code, c.name AS customer_name
FROM entity_records o
LEFT JOIN entity_projection_customer c
       ON c.id = o.customer_id
WHERE o.tenant_id=:t AND o.namespace='oms.order' AND o.entity_key='order' AND o.status='ACTIVE'
ORDER BY o.created_time DESC, o.id DESC
LIMIT 20;
```

---

# 6) 查询预设（Query Preset）示例

**Preset A：门店今日已支付**

```json
{
  "code": "store.paid.today",
  "entity_key": "order",
  "filters": [
    {"field":"store_id","op":"=","value":1001},
    {"field":"status_code","op":"=","value":"PAID"},
    {"field":"paid_time","op":">=","value":"${today 00:00:00}"},
    {"field":"paid_time","op":"<","value":"${tomorrow 00:00:00}"}
  ],
  "sort":[{"field":"paid_time","dir":"desc"}],
  "selects":["id","order_no","total_amount_cents","paid_time","channel_code"]
}
```

**Preset B：异常大额（≥ ¥10,000）近 7 天**

```json
{
  "code":"risk.large_amount.7d",
  "entity_key":"order",
  "filters":[
    {"field":"total_amount_cents","op":">=","value":1000000},
    {"field":"created_time","op":">=","value":"${now-7d}"}
  ],
  "sort":[{"field":"created_time","dir":"desc"}],
  "selects":["id","order_no","customer_id","total_amount_cents","created_time"]
}
```

---

# 7) BFF/服务层 API 载荷示例

## 列表查询（通用协议）

```json
POST /records/order/query
{
  "selects": ["id","order_no","status_code","total_amount_cents","paid_time","store_id"],
  "filters": [
    {"field":"store_id","op":"=","value":1001},
    {"field":"status_code","op":"in","value":["PAID","SHIPPED"]},
    {"field":"paid_time","op":">=","value":"2025-09-01T00:00:00+08:00"}
  ],
  "sort": [{"field":"paid_time","dir":"desc"},{"field":"id","dir":"desc"}],
  "page": {"size": 20}
}
```

## 新增订单

```json
POST /records/order
{
  "order_no": "O20250904-000123",
  "customer_id": 3456,
  "store_id": 1001,
  "status": "CREATED",
  "channel": "ONLINE",
  "currency": "CNY",
  "total_amount_cents": 259900,
  "created_at": "2025-09-04T10:21:33+08:00",
  "items": [
    {"sku_id": 90001, "name":"拿铁","qty":2, "price_cents": 19900},
    {"sku_id": 90002, "name":"可颂","qty":1, "price_cents": 120000}
  ],
  "shipping": {"receiver":"张三","phone":"13800000000","address":"杭州西湖区..."}
}
```

## 更新状态（支付）

```json
PATCH /records/order/{id}
{ "status": "PAID", "paid_at": "2025-09-04T10:25:00+08:00" }
```

---

# 8) 性能与治理要点（简明）

* **90% 查询**→ 走生成列索引：`store_id/status_code/created_time/paid_time/order_no/total_amount_cents/receiver_phone`
* **大表分页**→ Keyset + 复合索引（例：`paid_time DESC, id DESC`）
* **搜索**→ 订单号/手机号用表达式索引；多字段检索可加 `tsvector`
* **一致性**→ 冗余 label 时，用触发器或异步任务校准
* **归档**→ 180 天历史迁移至归档分区（可做时间分区表）
* **安全**→ 白名单字段 + 参数化 SQL；写操作记录审计


# 二、推荐：三层分离的“最佳实践”Schema

把“**语义/数据**”、“**呈现/UI**”、“**行为/权限**”三层拆开，各司其职，可在构建时或运行时合成。

## 1) Domain 层（语义/数据契约，跨端可复用）

* 标准字段字典：`fields[]`（code、type、enum、validators、i18nKey、format、compute等）
* 查询 DSL：`filters[]`（field、op、valueType、serverMapping/transform）
* 数据源：`dataSource`（endpoint、method、paramMap、transform、paging、sort、auth）

## 2) UI 层（呈现/布局，尽量与框架解耦）

* 表单布局：行/列/栅格的抽象（尽量通用，不写死 antd span）
* 控件绑定：`ui.controls[]`（bind: code、component、props、placeholderI18nKey…）
* 列渲染：`ui.table.columns[]`（bind、width、formatter、valueMapI18n、tagStyleMap）
* 交互元素：`ui.actions[]`（intent: view/edit/delete/new…，以及 icon、confirmI18nKey）

## 3) Behavior 层（可见性/权限/联动/副作用）

* 权限：`policy`（rbac/abac 条件表达式，如 `role in ['admin']`）
* 可见性/禁用：`visibleWhen/disabledWhen`（表达式，如 `status==='ACTIVE'`）
* 副作用：`events`（onChange/onLoad：触发远程选项加载、级联重置、toast、路由跳转）
* 审计/埋点：`audit`（actionId、eventName、extraFields）

---

# 三、合并版示例（“最佳实践”落地示例）

```json
{
  "meta": {
    "id": "store-list",
    "version": "1.1.0",
    "title": "page.store.title",
    "entity": "Store",
    "semver": true
  },
  "dataSource": {
    "endpoint": "/page/list/store-list",
    "method": "POST",
    "paging": { "type": "server", "pageField": "page", "sizeField": "size" },
    "sorting": { "type": "server", "field": "sortBy", "order": "order" },
    "auth": { "scopes": ["store:read"] },
    "transform": {
      "request": "fn(ctx){ /* 将 filters -> backend 查询参数，日期range 拆分 gte/lte */ return ctx; }",
      "response": "fn(res){ return { rows: res.data, total: res.total }; }"
    }
  },
  "domain": {
    "fields": [
      { "code": "name", "type": "string", "i18nKey": "store.name", "validators": [] },
      { "code": "code", "type": "string", "i18nKey": "store.code" },
      { "code": "type", "type": "enum", "i18nKey": "store.type",
        "enum": ["flagship","standard","convenience","specialty"],
        "labelMapI18n": {
          "flagship": "store.type.flagship",
          "standard": "store.type.standard",
          "convenience": "store.type.convenience",
          "specialty": "store.type.specialty"
        }
      },
      { "code": "status", "type": "enum", "i18nKey": "store.status",
        "enum": ["ACTIVE","INACTIVE","CLOSED"],
        "labelMapI18n": {
          "ACTIVE": "store.status.active",
          "INACTIVE": "store.status.inactive",
          "CLOSED": "store.status.closed"
        }
      },
      { "code": "openDate", "type": "date", "i18nKey": "store.openDate" },
      { "code": "createdAt", "type": "datetime", "i18nKey": "common.createdAt" },
      { "code": "pid", "type": "string", "private": true }
    ],
    "filters": [
      { "code": "keyword", "field": "keyword", "op": "LIKE", "valueType": "string",
        "i18nKey": "common.keyword" },
      { "code": "type", "field": "type", "op": "EQ", "valueType": "enum" },
      { "code": "status", "field": "status", "op": "EQ", "valueType": "enum" },
      { "code": "createdBetween", "field": "createdAt", "op": "BETWEEN",
        "valueType": "daterange",
        "serverMapping": {
          "splitTo": [
            { "field": "createdAt", "op": "GTE", "from": "start" },
            { "field": "createdAt", "op": "LTE", "from": "end" }
          ]
        }
      }
    ]
  },
  "ui": {
    "layout": { "form": { "direction": "horizontal", "labelWidth": 120 } },
    "controls": [
      { "bind": "keyword", "component": "Input", "props": { "placeholderI18n": "hint.keyword" } },
      { "bind": "type", "component": "Select",
        "props": { "optionsFrom": "domain.fields.type.labelMapI18n" } },
      { "bind": "status", "component": "Select",
        "props": { "optionsFrom": "domain.fields.status.labelMapI18n" } },
      { "bind": "createdBetween", "component": "DateRangePicker" }
    ],
    "table": {
      "rowKey": "pid",
      "columns": [
        { "bind": "name", "sortable": true, "width": 180 },
        { "bind": "code", "sortable": true, "width": 140 },
        { "bind": "type", "type": "Tag",
          "tagStyleMap": { "flagship": "info", "standard": "success", "convenience": "warning", "specialty": "purple" } },
        { "bind": "status", "type": "Tag",
          "tagStyleMap": { "ACTIVE": "success", "INACTIVE": "warning", "CLOSED": "danger" } },
        { "bind": "openDate", "type": "Date", "width": 130 },
        { "bind": "createdAt", "type": "DateTime", "sortable": true, "width": 180 },
        { "type": "Actions", "width": 220,
          "items": [
            { "intent": "view", "label": "action.view", "to": "/enterprise/stores/{pid}" },
            { "intent": "edit", "label": "action.edit", "to": "/enterprise/stores/{pid}/edit",
              "visibleWhen": "ctx.auth.has('store:write')" },
            { "intent": "delete", "label": "action.delete",
              "confirmI18n": "confirm.delete.store",
              "api": { "endpoint": "/enterprise/stores/{pid}", "method": "DELETE" },
              "visibleWhen": "ctx.auth.has('store:delete')" }
          ]
        }
      ],
      "pagination": { "pageSize": 10, "pageSizeOptions": [10,20,50,100], "showTotal": true }
    },
    "pageActions": [
      { "intent": "search", "label": "action.search", "primary": true },
      { "intent": "reset", "label": "action.reset" },
      { "intent": "new", "label": "store.new",
        "to": "/enterprise/stores/new",
        "visibleWhen": "ctx.auth.has('store:create')" }
    ],
    "batchActions": [
      { "intent": "batchDelete", "label": "action.batchDelete",
        "confirmI18n": "confirm.batchDelete.store",
        "api": { "endpoint": "/enterprise/stores/batch", "method": "DELETE", "bodyFrom": "selection.pids" },
        "visibleWhen": "ctx.auth.has('store:delete')" }
    ]
  },
  "events": {
    "policy": {
      "view": "ctx.auth.has('store:read')",
      "edit": "ctx.auth.has('store:write')"
    },
    "events": [
      { "on": "mounted", "action": "fetchOptions('type')" },
      { "on": "controls.status.change", "action": "audit('filter_status_changed')" }
    ],
    "audit": { "enabled": true, "events": ["search","view","edit","delete","batchDelete"] }
  }
}
```

**要点**

* **i18n 全面 code 化**：不直接写中文，UI 层按需渲染。
* **查询 DSL 标准化**：原 A 的 `operators` + 原 B 的 `apiConfig` → 统一成 `filters + serverMapping/transform`。
* **渲染与数据解耦**：UI tag/颜色是可选映射，换 UI 框架只需换渲染器。
* **权限 & 可见性**：统一在 `behavior.policy / visibleWhen` 管。
* **副作用**：`events` 显式声明（onLoad 取远程 options、联动清空等）。
* **数据源转化函数**：解决 date range 拆分、排序字段映射、响应扁平化。

---

# 六、可选增强清单

1. **字段字典复用**：把 type/status 等枚举放公共字典（多页面共享）。
2. **表达式标准化**：`visibleWhen/disabledWhen` 统一用 JEXL/SpEL 风格。
3. **远程枚举缓存**：`options` 支持 `source: remote` + `ttl`。
4. **列级权限**：对敏感列（营收等）加 `policy`。
5. **审计与埋点**：统一 actionId，方便后端记录。
6. **版本演进**：`version` 遵循 semver，非兼容变更需 `migration` 脚本。
7. **SSR/CSR 适配**：`dataSource` 增加 `defer`/`prefetch` 策略。
8. **错误与空态**：统一 empty/exception 配置（插图、说明、重试）。



可以，而且“三层分离（Domain/UI/Behavior）+ dataSource/filters/transform”的方案天生就适合做**列表查询 + 增删改查**。要点是把“语义契约、呈现、行为/权限”各自职责定清楚，再用 `transform` 把前端交互与后端接口对齐。下面给你一份**最小可行实现（可直接照抄改名）**，并解释各 CRUD 如何落地。

---

## 1) 列表查询（含分页、排序、筛选）

* **Domain.filters**：定义字段、运算符与值类型（`LIKE/EQ/BETWEEN...`）。
* **UI.controls**：绑定到 filters（Input/Select/DateRange）。
* **dataSource.paging/sorting**：声明“由服务端处理”。
* **transform.request**：把控件值→后端查询参数（含日期区间拆分、空值剔除、排序映射等）。

```json
{
  "meta": { "id": "store-list", "version": "1.1.0" },
  "dataSource": {
    "endpoint": "/bff/stores/search",
    "method": "POST",
    "paging": { "type": "server", "pageField": "page", "sizeField": "size" },
    "sorting": { "type": "server", "field": "sortBy", "order": "order" },
    "transform": {
      "request": "fn(ctx){ const {filters, page, size, sort} = ctx; const where=[]; if(filters.keyword){where.push({field:'name,code',op:'LIKE',value:filters.keyword});} if(filters.type){where.push({field:'type',op:'EQ',value:filters.type});} if(filters.status){where.push({field:'status',op:'EQ',value:filters.status});} if(filters.createdBetween){where.push({field:'createdAt',op:'GTE',value:filters.createdBetween.start}); where.push({field:'createdAt',op:'LTE',value:filters.createdBetween.end});} return { where, page, size, sortBy: sort?.field, order: sort?.order }; }",
      "response": "fn(res){ return { rows: res.data.list, total: res.data.total }; }"
    }
  },
  "domain": {
    "fields": [
      { "code": "name", "type": "string" },
      { "code": "code", "type": "string" },
      { "code": "type", "type": "enum", "enum": ["flagship","standard","convenience","specialty"] },
      { "code": "status", "type": "enum", "enum": ["ACTIVE","INACTIVE","CLOSED"] },
      { "code": "createdAt", "type": "datetime" },
      { "code": "version", "type": "int", "private": true },
      { "code": "pid", "type": "string", "private": true }
    ],
    "filters": [
      { "code": "keyword", "field": "keyword", "op": "LIKE", "valueType": "string" },
      { "code": "type", "field": "type", "op": "EQ", "valueType": "enum" },
      { "code": "status", "field": "status", "op": "EQ", "valueType": "enum" },
      { "code": "createdBetween", "field": "createdAt", "op": "BETWEEN", "valueType": "daterange" }
    ]
  },
  "ui": {
    "controls": [
      { "bind": "keyword", "component": "Input", "props": { "placeholder": "门店名称/编码" } },
      { "bind": "type", "component": "Select", "props": { "options": [
        {"label":"旗舰店","value":"flagship"},{"label":"标准店","value":"standard"},
        {"label":"便利店","value":"convenience"},{"label":"专卖店","value":"specialty"} ] } },
      { "bind": "status", "component": "Select", "props": { "options": [
        {"label":"活跃","value":"ACTIVE"},{"label":"暂停","value":"INACTIVE"},{"label":"关闭","value":"CLOSED"} ] } },
      { "bind": "createdBetween", "component": "DateRangePicker" }
    ],
    "table": {
      "rowKey": "pid",
      "columns": [
        { "bind": "name", "label": "门店名称", "sortable": true, "width": 180 },
        { "bind": "code", "label": "门店编码", "sortable": true, "width": 140 },
        { "bind": "type", "label": "类型", "type": "Tag" },
        { "bind": "status", "label": "状态", "type": "Tag" },
        { "bind": "createdAt", "label": "创建时间", "type": "DateTime", "sortable": true }
      ],
      "pagination": { "pageSize": 10, "pageSizeOptions": [10,20,50,100], "showTotal": true }
    },
    "pageActions": [
      { "intent": "search", "label": "查询", "primary": true },
      { "intent": "reset", "label": "重置" },
      { "intent": "new", "label": "新建门店", "openModal": "store-create" }
    ],
    "batchActions": [
      { "intent": "batchDelete", "label": "批量删除", "confirm": "确定要删除所选门店？",
        "api": { "endpoint": "/bff/stores/batch-delete", "method": "DELETE", "bodyFrom": "selection.pids" } }
    ]
  }
}
```

---

## 2) 新增 & 编辑（弹窗表单或单页表单）

* **表单重用 Domain.fields**（不会复制粘贴字段定义）。
* **UI.form** 指定控件、校验、placeholder；**Behavior.visibleWhen/disabledWhen** 控制显隐/联动。
* **dataSource.transform.request** 统一“创建/更新”payload；更新时带 `pid + version` 支持**乐观锁**。

```json
{
  "forms": [
    {
      "id": "store-create",
      "title": "新建门店",
      "domainRef": "store-list.domain.fields",
      "ui": {
        "layout": { "labelWidth": 100 },
        "controls": [
          { "bind": "name", "component": "Input", "props": { "required": true, "maxLength": 50 } },
          { "bind": "code", "component": "Input", "props": { "required": true } },
          { "bind": "type", "component": "Select", "props": { "required": true } },
          { "bind": "status", "component": "Select", "props": { "defaultValue": "ACTIVE" } }
        ],
        "submit": {
          "label": "保存",
          "api": {
            "endpoint": "/bff/stores",
            "method": "
```


可以，且会比“单层大而全 schema”更稳、可扩展。给你一份“如何用三层分离撑起完整 CRUD 列表”的落地说明（含最小示例片段），你照抄即可跑通。

# 1) 列表查询（Search/List）

* **Domain.filters**：声明字段、运算符、值类型与后端映射（BETWEEN→GTE/LTE 拆分、enum→后端码值）。
* **dataSource.paging/sorting/transform**：统一页码、大小、排序字段/顺序映射；`request/response` 适配把 UI 形态转换为后端契约。
* **UI.controls & UI.table.columns**：前者绑定搜索控件（输入框、选择器、日期范围…），后者定义列渲染、颜色映射、格式化。
* **Behavior.events**：`onMounted` 预取选项、`onSearch` 触发 `reload()`；`visibleWhen` 控制条件显隐（如选择“状态=关闭”才显示“关闭原因”过滤项）。

**示例（列表关键片段）**

```json
{
  "dataSource": {
    "endpoint": "/api/stores/search",
    "method": "POST",
    "paging": { "type": "server", "pageField": "page", "sizeField": "size" },
    "sorting": { "field": "sortBy", "order": "order" },
    "transform": {
      "request": "fn(p){ const [s,e]=p.filters.createdBetween||[]; return { ...p, createdAt_gte:s, createdAt_lte:e }; }",
      "response": "fn(res){ return { rows: res.items, total: res.total, page: res.page }; }"
    }
  },
  "domain": {
    "filters": [
      { "code":"keyword","field":"keyword","op":"LIKE","valueType":"string" },
      { "code":"type","field":"type","op":"EQ","valueType":"enum" },
      { "code":"status","field":"status","op":"EQ","valueType":"enum" },
      { "code":"createdBetween","field":"createdAt","op":"BETWEEN","valueType":"daterange" }
    ]
  }
}
```

# 2) 新建（Create）

* **命令与查询分离**：在 `dataSource` 下加 `commands.create`（或单独 `createDataSource`），避免把写操作塞进列表的查询端点。
* **UI**：`ui.dialog`/`ui.drawer` 打开“新建门店”表单；`ui.actions.new` 触发。
* **Domain.fields** 复用：同一字段字典用于列表列、表单控件、校验；减少重复与漂移。
* **Behavior**：`onSubmit → call(create) → toast → close → list.reload()`；失败回填后端校验消息。

**示例（新建关键片段）**

```json
{
  "commands": {
    "create": {
      "endpoint": "/api/stores",
      "method": "POST",
      "transform": {
        "request": "fn(v){ return { ...v, openDate: v.openDate?.toISOString?.() || v.openDate }; }"
      }
    }
  },
  "ui": {
    "pageActions": [
      { "intent":"new","label":"store.new","open":"drawer:createStore" }
    ],
    "drawers": [
      {
        "id":"createStore",
        "title":"store.new",
        "form": {
          "controls": [
            { "bind":"name","component":"Input","props":{"required":true} },
            { "bind":"code","component":"Input","props":{"required":true} },
            { "bind":"type","component":"Select","props":{"optionsFrom":"domain.fields.type.labelMapI18n"} },
            { "bind":"status","component":"Select","props":{"defaultValue":"ACTIVE"} },
            { "bind":"openDate","component":"DatePicker" }
          ],
          "submit": { "use":"commands.create" }
        },
        "afterSubmit": "fn(){ ctx.table.reload(); ctx.close(); ctx.toast.success(i18n('created.ok')); }"
      }
    ]
  }
}
```

# 3) 编辑（Update）

* **读 + 写**：编辑前先 `queries.getById` 拉取初始值；提交走 `commands.update`。
* **并发控制**：在 Domain.fields 增加 `version`（或 `updatedAt`），表单提交时带上，后端用乐观锁（409 冲突给出并发提示）。
* **差异提交**：可在 `transform.request` 里只提交变更字段（可选）。

**示例（编辑关键片段）**

```json
{
  "queries": {
    "getById": { "endpoint": "/api/stores/{pid}", "method": "GET" }
  },
  "commands": {
    "update": { "endpoint": "/api/stores/{pid}", "method": "PUT" }
  },
  "ui": {
    "table": {
      "columns": [
        { "bind":"name" }, { "bind":"code" },
        { "type":"Actions","items":[
          { "intent":"view","to":"/enterprise/stores/{pid}" },
          { "intent":"edit","open":"drawer:editStore","visibleWhen":"ctx.auth.has('store:write')" }
        ]}
      ]
    },
    "drawers": [
      {
        "id":"editStore",
        "title":"store.edit",
        "initialData": { "use":"queries.getById" },
        "form": {
          "controls":[ { "bind":"name" },{ "bind":"type" },{ "bind":"status" },{ "bind":"openDate" },{ "bind":"version","component":"Hidden" } ],
          "submit": { "use":"commands.update" }
        },
        "afterSubmit":"fn(){ ctx.table.reloadKeepPage(); ctx.close(); ctx.toast.success(i18n('updated.ok')); }"
      }
    ]
  }
}
```

# 4) 删除（Delete & 批量删除）

* **行级删除**：`rowAction.delete` 配 `confirm`、`policy`、`commands.delete`。
* **批量删除**：在 `ui.batchActions` 里配置，`bodyFrom: selection.pids`。
* **软删/硬删**：通过 `commands.delete.endpoint` 区分 `/delete`（软删）或 `DELETE /:id`（硬删），UI 上用 `visibleWhen` 隐藏不适用操作。

**示例（删除关键片段）**

```json
{
  "commands": {
    "delete": { "endpoint": "/api/stores/{pid}", "method": "DELETE" },
    "batchDelete": { "endpoint": "/api/stores/batch", "method": "DELETE", "bodyFrom":"selection.pids" }
  },
  "ui": {
    "table": {
      "rowSelection": "multiple",
      "columns": [
        { "bind":"status" },
        { "type":"Actions","items":[
          { "intent":"delete","confirmI18n":"confirm.delete.store","use":"commands.delete","visibleWhen":"ctx.auth.has('store:delete')" }
        ]}
      ]
    },
    "batchActions": [
      { "intent":"batchDelete","label":"action.batchDelete","confirmI18n":"confirm.batchDelete.store","use":"commands.batchDelete","visibleWhen":"ctx.auth.has('store:delete')" }
    ]
  }
}
```

# 5) 关键非功能性点（CRUD 体验更稳）

* **权限**：`behavior.policy` 统一声明；`visibleWhen/disabledWhen` 控制按钮/字段的可见与可操作。
* **校验**：Domain.validators（必填、正则、唯一性）；后端错误透传，以字段级 message 回填。
* **并发与幂等**：`version` 乐观锁 + `Idempotency-Key`（可选）保证重复提交安全。
* **大数据列表**：分页必须服务端；支持条件变更时自动回到第 1 页；导出通过后台任务 + 回传下载链接。
* **审计**：`behavior.audit` 统一记录（search/view/create/update/delete/batchDelete）。
* **国际化**：全量 i18n code（标题、占位、确认文案、错误提示、tag 文本/颜色映射）。
* **可观测性**：为每个 `query/command` 统一埋点（耗时、成功率、错误码）。

绝对可以把这份“三层分离”方案升级为“**字典驱动**”的低代码形态。核心目标是：**字段定义、枚举项、格式校验、显示样式和权限**都由**数据字典**统一出数，Schema 只做绑定与组合。下面给出完整的**设计建议 + 可直接套用的 Schema 改造示例**。

---

# 0. 设计目标（为什么要引入数据字典）

1. **单一事实来源（SSOT）**：类型、状态、标签文案、颜色、图标、顺序、正则、单位精度等，不再散落在 UI 或接口里。
2. **低耦合/易演进**：新增/禁用枚举项、重命名标签、换色，不需要改代码或重新发版。
3. **多租户/多语言**：支持“平台默认 → 租户覆盖 → 组织覆盖 → 语言覆盖”的分层合并。
4. **一致性**：表单、列表、导入导出、校验、报表的码值与文案强一致。

---

# 1. 数据字典的模型（建议）

## 1.1 字典类型（DictionaryType）

* `code`：如 `store.type`、`store.status`
* `name`：类型名称 i18n code
* `valueType`：`string|int|bool|decimal|json`
* `hierarchical`：是否树形（用于省市区、品类）
* `attributes`：对**所有项**生效的公共属性约束（如需颜色/图标）
* `scope`：`global | tenant | org`（并支持层级覆盖）
* `version`：SemVer，支持灰度
* `extends`：（可选）继承父类型

## 1.2 字典项（DictionaryItem）

* `typeKey`：指向 `DictionaryType`
* `code`：存储码值（后端入库、接口传输用）
* `label`：显示文案 code（多语言）
* `status`：`ACTIVE|INACTIVE|DEPRECATED`（控制是否可选/只读/隐藏）
* `order`：列表/下拉显示顺序
* `parentCode`：树形时使用
* `attrs`：如 `color`, `icon`, `badge`, `tagStyle`，或扩展键值对
* `effectiveFrom/To`：生效区间（便于策略/活动）

## 1.3 字段字典（FieldCatalog）

* `entityCode` / `code` / `dbColumn`
* `dataType`、`length/precision`、`nullable`、`default`
* `dictRef`：（可选）绑定某字典类型，如 `store.type`
* `unitRef`：如 `currency.CNY`、`length.cm`
* `validators`：正则/唯一性/范围（也可以引用公共 validator）
* `maskPolicy/piiClass`：脱敏与隐私分级（列表与导出要遵守）
* `searchable/sortable/indexHints`：供生成器/查询构建器使用

> **实现位置**：可以是独立 `dictionary` 服务，也可以作为“平台配置中心”的一个域模型。对低代码 IDE 开放选择器：**字段绑定 → dictRef 选择**。

---

# 2. 你的 Schema 如何接入数据字典（改造点）

下面基于你发来的 schema 做“**最小改造**”，把硬编码枚举/文案/颜色，全部换成**字典引用**。

## 2.1 Domain 层：用 `dictRef` 替代硬编码枚举与 labelMap

* 把 `type/status` 的 `enum + labelMapI18n` 去掉，改为 `dictRef`。
* 校验（必填、正则、枚举约束）优先读取 `FieldCatalog.validators`。

```json
{
  "domain": {
    "fields": [
      { "code": "name", "type": "string", "i18nKey": "store.name" },
      { "code": "code", "type": "string", "i18nKey": "store.code" },
      { "code": "type", "type": "string", "i18nKey": "store.type", "dictRef": "store.type" },
      { "code": "status", "type": "string", "i18nKey": "store.status", "dictRef": "store.status" },
      { "code": "openDate", "type": "date", "i18nKey": "store.openDate" },
      { "code": "createdAt", "type": "datetime", "i18nKey": "common.createdAt" },
      { "code": "pid", "type": "string", "private": true }
    ],
    "filters": [
      { "code": "keyword", "field": "keyword", "op": "LIKE", "valueType": "string", "i18nKey": "common.keyword" },
      { "code": "type", "field": "type", "op": "EQ", "valueType": "enum", "dictRef": "store.type" },
      { "code": "status", "field": "status", "op": "EQ", "valueType": "enum", "dictRef": "store.status" },
      { "code": "createdBetween", "field": "createdAt", "op": "BETWEEN", "valueType": "daterange",
        "serverMapping": { "splitTo": [
          { "field": "createdAt", "op": "GTE", "from": "start" },
          { "field": "createdAt", "op": "LTE", "from": "end" }
        ]}
      }
    ]
  }
}
```

## 2.2 UI 层：用 `dict:` 数据源与字典属性渲染

* `Select` 的 `optionsFrom` 改为 `dict:store.type` / `dict:store.status`。
* `Tag` 的颜色不再硬编码，直接**读字典项 attrs**（如 `attrs.color` 或 `attrs.tagStyle`）。

```json
{
  "ui": {
    "controls": [
      { "bind":"keyword","component":"Input","props":{"placeholderI18n":"hint.keyword"} },
      { "bind":"type","component":"Select","props":{"optionsFrom":"dict:store.type"} },
      { "bind":"status","component":"Select","props":{"optionsFrom":"dict:store.status"} },
      { "bind":"createdBetween","component":"DateRangePicker" }
    ],
    "table": {
      "rowKey": "pid",
      "columns": [
        { "bind": "name", "sortable": true, "width": 180 },
        { "bind": "code", "sortable": true, "width": 140 },
        { "bind": "type", "type": "Tag",
          "dictRef": "store.type",
          "tagStyleMapFrom": "dictAttr:tagStyle|color"    // 优先取 tagStyle，退化取 color
        },
        { "bind": "status", "type": "Tag",
          "dictRef": "store.status",
          "tagStyleMapFrom": "dictAttr:tagStyle|color"
        },
        { "bind": "openDate", "type": "Date", "width": 130 },
        { "bind": "createdAt", "type": "DateTime", "sortable": true, "width": 180 },
        { "type": "Actions", "width": 220,
          "items": [
            { "intent":"view","label":"action.view","to":"/enterprise/stores/{pid}" },
            { "intent":"edit","label":"action.edit","to":"/enterprise/stores/{pid}/edit", "visibleWhen":"ctx.auth.has('store:write')" },
            { "intent":"delete","label":"action.delete","confirmI18n":"confirm.delete.store",
              "api":{"endpoint":"/enterprise/stores/{pid}","method":"DELETE"},
              "visibleWhen":"ctx.auth.has('store:delete')" }
          ]
        }
      ]
    }
  }
}
```

> UI 渲染器侧要支持：
>
> * `optionsFrom: "dict:<typeKey>"` 自动拉取字典项，处理禁用/隐藏/排序。
> * `tagStyleMapFrom: "dictAttr:<attrKey1>|<fallbackAttrKey2>"` 从字典项属性读取样式。
> * `label` 渲染时优先 `label`，没有则回退 `code`。

## 2.3 Behavior 层：可见性/权限/审计可读字典

* 某些行为依赖字典值（如“状态=关闭”才显示“关闭原因”），用表达式读取码值即可：`visibleWhen: "ctx.form.status==='CLOSED'"`。
* 审计事件可携带**字典显示值**（更可读），由渲染器在记录前做一次 `code→label` 映射。

---

# 3. 字典服务/缓存/发布策略（运行时建议）

1. **统一 API（示例）**

    * `GET /dict/types/{typeKey}`：取类型定义（valueType、attrs 模板、版本信息）
    * `GET /dict/items?type={typeKey}&locale=zh-CN&tenantId=...`：按**租户+语言**合成后的项
    * `GET /dict/fields/{entity}.{field}`：取 `FieldCatalog`（含 validators、dictRef、maskPolicy 等）

2. **多层合并**

    * 合并顺序：`global → tenant → org → locale`，后者覆盖前者的 `label/attrs/status/order`。
    * 支持灰度：`?version=1.2.0` 或 `Accept-Dict-Version: 1.2.0` 头；响应带 `ETag`/`Last-Modified`。

3. **缓存**

    * 前端：按 `typeKey + locale + tenantId + version` 缓存，TTL（如 10 分钟）+ ETag 协商更新。
    * BFF：可做全局缓存与预热，避免多端重复拉取。

4. **离线回退**

    * 关键字典（如 `store.status`）随前端打包内置“只读快照”，网络失败时回退，成功后热更新。

---

# 4. 与 CRUD 列表/导入导出/报表的结合

* **查询**：filters 的 `dictRef` 让 IDE 自动渲染下拉；请求仍传 `code` 到后端，避免传 `label`。
* **新增/编辑**：表单控件用 `dict:xxx` 渲染；提交前校验 code 是否在当前版本字典项中（防止脏数据）。
* **删除/批删**：与字典无直接耦合，但审计时可把 `status` 的 label 同步记录，便于审计阅读。
* **导出**：默认导出 `code`；可配置 `export.label=true` 同时导出显示文案；导入时支持 `code/label` 智能匹配（优先 code）。
* **报表**：维度展开用字典项顺序 `order`；历史报表可绑定“字典快照版本”（保证复现当时口径）。

---

# 5. 字典治理与演进（务必落地）

* **版本策略**：

    * `patch`：改文案/顺序/颜色（兼容）
    * `minor`：新增项（兼容）
    * `major`：删除/替换项（不兼容）→ 需要迁移计划
* **废弃流程**：项状态先标 `DEPRECATED`（前端灰显或禁止新选），观察 1\~2 个版本再移除。
* **变更审计**：记录“谁在何时修改了哪些字典项/属性”，并通知到使用该字典的页面（IDE 内提醒）。
* **回归测试**：字典作为数据驱动，需有**快照测试**（变更后渲染一致性）。

---

# 6. 一份更“字典化”的完整片段（可直接替换你现有的关键字段）

```json
{
  "meta": { "id": "store-list", "version": "1.1.1", "title": "page.store.title", "entity": "Store", "semver": true },
  "dataSource": {
    "endpoint": "/page/list/store-list",
    "method": "POST",
    "paging": { "type": "server", "pageField": "page", "sizeField": "size" },
    "sorting": { "type": "server", "field": "sortBy", "order": "order" },
    "auth": { "scopes": ["store:read"] },
    "transform": {
      "request": "fn(ctx){ /* filters中若存在label值，统一转code；日期range->gte/lte */ return ctx; }",
      "response": "fn(res){ return { rows: res.data, total: res.total }; }"
    }
  },
  "domain": {
    "fields": [
      { "code":"name","type":"string","i18nKey":"store.name" },
      { "code":"code","type":"string","i18nKey":"store.code" },
      { "code":"type","type":"string","i18nKey":"store.type","dictRef":"store.type" },
      { "code":"status","type":"string","i18nKey":"store.status","dictRef":"store.status" },
      { "code":"openDate","type":"date","i18nKey":"store.openDate" },
      { "code":"createdAt","type":"datetime","i18nKey":"common.createdAt" },
      { "code":"pid","type":"string","private":true }
    ],
    "filters": [
      { "code":"keyword","field":"keyword","op":"LIKE","valueType":"string","i18nKey":"common.keyword" },
      { "code":"type","field":"type","op":"EQ","valueType":"enum","dictRef":"store.type" },
      { "code":"status","field":"status","op":"EQ","valueType":"enum","dictRef":"store.status" },
      { "code":"createdBetween","field":"createdAt","op":"BETWEEN","valueType":"daterange",
        "serverMapping":{"splitTo":[
          {"field":"createdAt","op":"GTE","from":"start"},
          {"field":"createdAt","op":"LTE","from":"end"} ]}}
    ]
  },
  "ui": {
    "layout": { "form": { "direction": "horizontal", "labelWidth": 120 } },
    "controls": [
      { "bind":"keyword","component":"Input","props":{"placeholderI18n":"hint.keyword"} },
      { "bind":"type","component":"Select","props":{"optionsFrom":"dict:store.type"} },
      { "bind":"status","component":"Select","props":{"optionsFrom":"dict:store.status"} },
      { "bind":"createdBetween","component":"DateRangePicker" }
    ],
    "table": {
      "rowKey":"pid",
      "columns":[
        { "bind":"name","sortable":true,"width":180 },
        { "bind":"code","sortable":true,"width":140 },
        { "bind":"type","type":"Tag","dictRef":"store.type","tagStyleMapFrom":"dictAttr:tagStyle|color" },
        { "bind":"status","type":"Tag","dictRef":"store.status","tagStyleMapFrom":"dictAttr:tagStyle|color" },
        { "bind":"openDate","type":"Date","width":130 },
        { "bind":"createdAt","type":"DateTime","sortable":true,"width":180 },
        { "type":"Actions","width":220,"items":[
          { "intent":"view","label":"action.view","to":"/enterprise/stores/{pid}" },
          { "intent":"edit","label":"action.edit","to":"/enterprise/stores/{pid}/edit","visibleWhen":"ctx.auth.has('store:write')" },
          { "intent":"delete","label":"action.delete","confirmI18n":"confirm.delete.store",
            "api":{"endpoint":"/enterprise/stores/{pid}","method":"DELETE"},
            "visibleWhen":"ctx.auth.has('store:delete')" }
        ]}
      ],
      "pagination":{"pageSize":10,"pageSizeOptions":[10,20,50,100],"showTotal":true}
    }
  },
  "events": {
    "policy": { "view":"ctx.auth.has('store:read')", "edit":"ctx.auth.has('store:write')" },
    "events": [
      { "on":"mounted", "action":"dict.preload(['store.type','store.status'])" },
      { "on":"controls.status.change", "action":"audit('filter_status_changed')" }
    ],
    "audit": { "enabled": true, "events": ["search","view","edit","delete","batchDelete"] }
  }
}
```

---

# 7. 实施清单（落地即可用）

* [ ] 建立 `dictionary` 域：类型、项、字段字典三表（含多租户/多语言）
* [ ] 提供统一 API + 前端/BFF 缓存与 ETag 协商
* [ ] 低代码 IDE：字段配置面板支持 `dictRef` 选择器；下拉控件支持 `optionsFrom: dict:<typeKey>`
* [ ] UI 渲染器：支持 `tagStyleMapFrom: dictAttr:...`、`label` 回退策略
* [ ] 导入导出：支持 code/label 来回映射；导入校验不合法项
* [ ] 治理：字典变更流程、灰度发布、快照回滚、快照测试

---

**一句话总结**：
把“枚举/文案/颜色/校验”从页面 schema 中全部抽离到**数据字典**，你的 `Domain/UI/Behavior` 只做**绑定与编排**。这样 CRUD 列表更稳定、跨租户/跨语言更优雅、长期演进成本更低。
