-- ============================================
-- 多租户环境下初始化门店相关数据源
-- 支持为多个租户批量创建数据
-- ============================================

-- 创建临时函数：为指定租户初始化门店数据源
CREATE OR REPLACE FUNCTION init_store_datasources_for_tenant(
  p_tenant_id BIGINT,
  p_namespace TEXT DEFAULT 'store',
  p_env TEXT DEFAULT 'prod'
) RETURNS VOID AS $$
BEGIN
  -- 1. 插入门店类型数据源
  INSERT INTO ab_data_source (
    pid,
    tenant_id,
    namespace,
    env,
    version,
    is_current,
    status,
    code,
    type,
    items,
    extension
  ) VALUES (
    'ds_storeTypes_' || p_tenant_id,
    p_tenant_id,
    p_namespace,
    p_env,
    1,
    TRUE,
    'published',
    'ds_storeTypes',
    'static',
    '[
      {
        "value": "flagship",
        "label": "旗舰店",
        "name": "旗舰店",
        "code": "flagship",
        "description": "品牌旗舰店，提供全系列产品和服务"
      },
      {
        "value": "standard",
        "label": "标准店",
        "name": "标准店",
        "code": "standard",
        "description": "标准门店，提供常规产品和服务"
      },
      {
        "value": "express",
        "label": "便利店",
        "name": "便利店",
        "code": "express",
        "description": "小型便利店，提供快捷服务"
      },
      {
        "value": "outlet",
        "label": "奥特莱斯",
        "name": "奥特莱斯",
        "code": "outlet",
        "description": "折扣店，销售过季商品"
      },
      {
        "value": "popup",
        "label": "快闪店",
        "name": "快闪店",
        "code": "popup",
        "description": "临时性门店，限时营业"
      }
    ]'::jsonb,
    '{
      "category": "store_management",
      "displayOrder": 1,
      "updatable": true,
      "cacheable": true,
      "cacheExpireSeconds": 3600
    }'::jsonb
  ) ON CONFLICT (pid) DO UPDATE SET
    items = EXCLUDED.items,
    updated_at = now();

  -- 2. 插入门店状态数据源
  INSERT INTO ab_data_source (
    pid,
    tenant_id,
    namespace,
    env,
    version,
    is_current,
    status,
    code,
    type,
    items,
    extension
  ) VALUES (
    'ds_storeStatuses_' || p_tenant_id,
    p_tenant_id,
    p_namespace,
    p_env,
    1,
    TRUE,
    'published',
    'ds_storeStatuses',
    'static',
    '[
      {
        "value": "active",
        "label": "营业中",
        "name": "营业中",
        "code": "active",
        "color": "success",
        "icon": "check-circle",
        "description": "门店正常营业"
      },
      {
        "value": "inactive",
        "label": "暂停营业",
        "name": "暂停营业",
        "code": "inactive",
        "color": "warning",
        "icon": "pause-circle",
        "description": "门店临时暂停营业"
      },
      {
        "value": "preparing",
        "label": "筹备中",
        "name": "筹备中",
        "code": "preparing",
        "color": "info",
        "icon": "clock",
        "description": "门店正在筹备，尚未开业"
      },
      {
        "value": "renovating",
        "label": "装修中",
        "name": "装修中",
        "code": "renovating",
        "color": "warning",
        "icon": "wrench",
        "description": "门店正在装修改造"
      },
      {
        "value": "closed",
        "label": "已关闭",
        "name": "已关闭",
        "code": "closed",
        "color": "error",
        "icon": "x-circle",
        "description": "门店已永久关闭"
      }
    ]'::jsonb,
    '{
      "category": "store_management",
      "displayOrder": 2,
      "updatable": true,
      "cacheable": true,
      "cacheExpireSeconds": 3600,
      "statusFlow": ["preparing", "active", "inactive", "renovating", "closed"]
    }'::jsonb
  ) ON CONFLICT (pid) DO UPDATE SET
    items = EXCLUDED.items,
    updated_at = now();

  RAISE NOTICE 'Store datasources initialized for tenant_id: %', p_tenant_id;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- 批量初始化示例
-- ============================================

-- 方式1: 为单个租户初始化
SELECT init_store_datasources_for_tenant(1);

-- 方式2: 为多个租户批量初始化 (示例: tenant_id 1-5)
-- SELECT init_store_datasources_for_tenant(tenant_id)
-- FROM generate_series(1, 5) AS tenant_id;

-- 方式3: 为所有现有租户初始化 (假设有 ab_tenant 表)
-- SELECT init_store_datasources_for_tenant(id)
-- FROM ab_tenant
-- WHERE deleted_flag = FALSE;

-- ============================================
-- 查询验证
-- ============================================

-- 查看所有租户的门店类型数据源
SELECT
  tenant_id,
  code,
  type,
  status,
  is_current,
  jsonb_array_length(items) as item_count,
  created_at
FROM ab_data_source
WHERE code = 'ds_storeTypes'
  AND is_current = TRUE
ORDER BY tenant_id;

-- 查看所有租户的门店状态数据源
SELECT
  tenant_id,
  code,
  type,
  status,
  is_current,
  jsonb_array_length(items) as item_count,
  created_at
FROM ab_data_source
WHERE code = 'ds_storeStatuses'
  AND is_current = TRUE
ORDER BY tenant_id;

-- 查看特定租户的完整数据
SELECT
  tenant_id,
  code,
  items -> 0 as first_item,
  items -> 1 as second_item,
  jsonb_array_length(items) as total_items
FROM ab_data_source
WHERE tenant_id = 1
  AND code IN ('ds_storeTypes', 'ds_storeStatuses')
  AND is_current = TRUE;

-- ============================================
-- 清理脚本 (仅用于开发/测试环境)
-- ============================================

-- CAUTION: 仅在需要重置数据时使用

-- 删除特定租户的数据源
-- DELETE FROM ab_data_source
-- WHERE tenant_id = 1
--   AND code IN ('ds_storeTypes', 'ds_storeStatuses');

-- 删除所有租户的门店数据源
-- DELETE FROM ab_data_source
-- WHERE code IN ('ds_storeTypes', 'ds_storeStatuses');

-- 删除临时函数
-- DROP FUNCTION IF EXISTS init_store_datasources_for_tenant(BIGINT, TEXT, TEXT);
