# 租户初始化模板

本目录包含租户初始化模板文件，用于在创建新租户时自动配置角色、菜单和权限。

## 模板文件

### default-bootstrap.json

默认租户初始化模板，包含标准的角色、菜单和权限配置。

## 模板结构

每个模板文件包含以下部分：

### 1. 元数据

```json
{
  "$schema": "https://auraboot.com/schemas/tenant-bootstrap/v1.0.0",
  "version": "1.0.0",
  "name": "default-bootstrap",
  "description": "模板描述"
}
```

- `$schema`: 模板schema定义URL
- `version`: 模板版本号（格式：major.minor.patch）
- `name`: 模板名称（用于标识不同模板）
- `description`: 模板描述

### 2. 权限定义 (permissions)

定义租户初始化时需要确保存在的系统级权限：

```json
{
  "code": "MODEL_CONFIG",
  "name": "模型配置",
  "description": "访问和管理元数据模型配置",
  "type": "MENU",
  "module": "META",
  "resource": "/meta/models",
  "action": "view",
  "resourceType": "MENU"
}
```

字段说明：
- `code`: 权限代码（必填，全局唯一标识）
- `name`: 权限名称（必填）
- `description`: 权限描述
- `type`: 权限类型（MENU/API/BUTTON，默认：MENU）
- `module`: 所属模块（META/RBAC/TENANT等）
- `resource`: 资源标识（路径或资源标识符）
- `action`: 操作类型（view/create/update/delete/manage）
- `resourceType`: 资源类型（MENU/API/ENTITY/FIELD/DICT）

**重要说明**：
- 权限会被创建为**系统级权限**（tenant_id = NULL），所有租户共享
- 如果权限已存在，会跳过创建（幂等性）
- 菜单的`permissionCode`必须引用这里定义的权限

### 3. 角色定义 (roles)

定义租户初始化时需要创建的角色：

```json
{
  "code": "TENANT_ADMIN",
  "name": "租户管理员",
  "description": "角色描述",
  "type": "TENANT",
  "scopeType": "TENANT",
  "priority": 1,
  "isDefault": true,
  "isDeletable": false
}
```

字段说明：
- `code`: 角色编码（必填，唯一标识）
- `name`: 角色名称（必填）
- `description`: 角色描述
- `type`: 角色类型（TENANT/SYSTEM）
- `scopeType`: 作用域类型（TENANT/GLOBAL）
- `priority`: 优先级（数值越小优先级越高）
- `isDefault`: 是否为默认角色
- `isDeletable`: 是否可删除

### 4. 菜单定义 (menus)

定义租户初始化时需要创建的菜单结构：

```json
{
  "code": "CONTENT_CREATION",
  "parentCode": null,
  "name": "节目制作",
  "path": "/content",
  "component": null,
  "icon": "content-icon",
  "type": 0,
  "permissionCode": "CONTENT_CREATION",
  "orderNo": 10,
  "visible": true
}
```

字段说明：
- `code`: 菜单编码（必填，唯一标识）
- `parentCode`: 父菜单编码（null表示顶级菜单）
- `name`: 菜单名称（必填）
- `path`: 路由路径（必填）
- `component`: 前端组件名称（目录类型可为null）
- `icon`: 菜单图标
- `type`: 菜单类型（0=目录，1=菜单）
- `permissionCode`: 关联的权限代码（必须在权限表中存在）
- `orderNo`: 排序号（数值越小越靠前）
- `visible`: 是否可见

### 5. 角色-权限绑定 (rolePermissionBindings)

定义角色与权限的关联关系：

```json
{
  "roleCode": "TENANT_ADMIN",
  "permissionCodes": [
    "CONTENT_CREATION",
    "AI_IMAGE_GENERATION",
    "WEB_EDITOR"
  ]
}
```

字段说明：
- `roleCode`: 角色编码（必填，引用roles中定义的角色）
- `permissionCodes`: 权限代码列表（必填，至少包含一个）
  - 特殊值 "*" 表示拥有所有权限

## 使用方法

### 1. 系统默认使用

系统启动时会自动加载 `default-bootstrap.json` 模板。

### 2. 创建自定义模板

1. 复制 `default-bootstrap.json` 文件
2. 修改模板名称和内容
3. 保存为新的JSON文件（例如：`enterprise-bootstrap.json`）
4. 在 `application.yml` 中配置使用的模板名称

### 3. 配置示例

```yaml
aura:
  tenant:
    bootstrap:
      default-template-name: default-bootstrap
```

## 验证规则

模板加载时会进行以下验证：

1. **必填字段验证**
   - 权限的 `code` 和 `name` 必须存在
   - 角色的 `code` 和 `name` 必须存在
   - 菜单的 `code`、`name` 和 `path` 必须存在
   - 角色-权限绑定的 `roleCode` 和 `permissionCodes` 必须存在

2. **数据完整性验证**
   - 菜单的 `parentCode` 必须引用已定义的菜单
   - 菜单的 `permissionCode` 必须在权限定义中存在
   - 角色-权限绑定的 `roleCode` 必须引用已定义的角色

3. **权限-菜单一致性验证**
   - 所有菜单引用的权限必须在权限表中存在（会自动创建）
   - 验证失败会抛出异常并回滚事务

## 权限处理机制

系统采用**混合方案**处理权限：

1. **系统级权限**：模板中定义的权限会被创建为系统级权限（tenant_id = NULL）
2. **自动创建**：租户初始化时，会检查权限是否存在，不存在则自动创建
3. **幂等性**：重复初始化不会创建重复的权限
4. **共享机制**：所有租户共享系统级权限，节省存储空间

## 注意事项

1. **权限定义**：模板中必须定义所有菜单引用的权限
2. **菜单层级**：建议菜单层级不超过3层，以保持界面简洁
3. **角色优先级**：TENANT_ADMIN应该具有最高优先级（最小数值）
4. **不可删除角色**：TENANT_ADMIN角色应设置为不可删除（isDeletable=false）
5. **JSON格式**：确保JSON文件格式正确，使用UTF-8编码
6. **权限唯一性**：权限code必须全局唯一，不同租户不能定义相同code的权限

## 版本历史

- v1.0.0 (2024-12-30): 初始版本，包含基础角色、菜单和权限配置
- v1.1.0 (2024-12-30): 添加权限定义支持，实现系统级权限自动创建
