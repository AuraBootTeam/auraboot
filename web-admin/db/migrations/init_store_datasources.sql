-- ============================================
-- 初始化门店相关数据源
-- ============================================

-- 1. 门店类型 (ds_storeTypes)
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
  'ds_storeTypes',
  1,  -- 默认租户 ID，根据实际情况调整
  'store',
  'prod',
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

-- 2. 门店状态 (ds_storeStatuses)
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
  'ds_storeStatuses',
  1,  -- 默认租户 ID，根据实际情况调整
  'store',
  'prod',
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

-- ============================================
-- 验证查询
-- ============================================

-- 查询门店类型数据源
SELECT
  code,
  type,
  status,
  jsonb_array_length(items) as item_count,
  items
FROM ab_data_source
WHERE code = 'ds_storeTypes'
  AND is_current = TRUE;

-- 查询门店状态数据源
SELECT
  code,
  type,
  status,
  jsonb_array_length(items) as item_count,
  items
FROM ab_data_source
WHERE code = 'ds_storeStatuses'
  AND is_current = TRUE;

-- ============================================
-- 清理脚本 (仅用于开发/测试环境)
-- ============================================

-- CAUTION: 仅在需要重置数据时使用
-- DELETE FROM ab_data_source WHERE code IN ('ds_storeTypes', 'ds_storeStatuses');
