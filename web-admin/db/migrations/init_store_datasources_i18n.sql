-- ============================================
-- 国际化版本的门店数据源初始化
-- 支持 zh-CN, en-US, ja-JP, ko-KR
-- ============================================

-- 1. 门店类型 (ds_storeTypes) - 国际化版本
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
  1,
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
      "code": "flagship",
      "label": "旗舰店",
      "name": "旗舰店",
      "i18n": {
        "zh-CN": {
          "label": "旗舰店",
          "description": "品牌旗舰店，提供全系列产品和服务"
        },
        "en-US": {
          "label": "Flagship Store",
          "description": "Brand flagship store offering full product line and services"
        },
        "ja-JP": {
          "label": "フラッグシップストア",
          "description": "ブランドのフラッグシップストアで、全製品ラインとサービスを提供"
        },
        "ko-KR": {
          "label": "플래그십 스토어",
          "description": "전체 제품 라인과 서비스를 제공하는 브랜드 플래그십 스토어"
        }
      }
    },
    {
      "value": "standard",
      "code": "standard",
      "label": "标准店",
      "name": "标准店",
      "i18n": {
        "zh-CN": {
          "label": "标准店",
          "description": "标准门店，提供常规产品和服务"
        },
        "en-US": {
          "label": "Standard Store",
          "description": "Standard store offering regular products and services"
        },
        "ja-JP": {
          "label": "スタンダードストア",
          "description": "通常の製品とサービスを提供する標準店舗"
        },
        "ko-KR": {
          "label": "표준 매장",
          "description": "일반 제품 및 서비스를 제공하는 표준 매장"
        }
      }
    },
    {
      "value": "express",
      "code": "express",
      "label": "便利店",
      "name": "便利店",
      "i18n": {
        "zh-CN": {
          "label": "便利店",
          "description": "小型便利店，提供快捷服务"
        },
        "en-US": {
          "label": "Express Store",
          "description": "Compact convenience store with quick service"
        },
        "ja-JP": {
          "label": "コンビニエンスストア",
          "description": "迅速なサービスを提供する小型コンビニエンスストア"
        },
        "ko-KR": {
          "label": "편의점",
          "description": "빠른 서비스를 제공하는 소형 편의점"
        }
      }
    },
    {
      "value": "outlet",
      "code": "outlet",
      "label": "奥特莱斯",
      "name": "奥特莱斯",
      "i18n": {
        "zh-CN": {
          "label": "奥特莱斯",
          "description": "折扣店，销售过季商品"
        },
        "en-US": {
          "label": "Outlet Store",
          "description": "Discount store selling off-season items"
        },
        "ja-JP": {
          "label": "アウトレット",
          "description": "シーズンオフ商品を販売するディスカウントストア"
        },
        "ko-KR": {
          "label": "아울렛",
          "description": "시즌 오프 상품을 판매하는 할인 매장"
        }
      }
    },
    {
      "value": "popup",
      "code": "popup",
      "label": "快闪店",
      "name": "快闪店",
      "i18n": {
        "zh-CN": {
          "label": "快闪店",
          "description": "临时性门店，限时营业"
        },
        "en-US": {
          "label": "Pop-up Store",
          "description": "Temporary store with limited-time operation"
        },
        "ja-JP": {
          "label": "ポップアップストア",
          "description": "期間限定の臨時店舗"
        },
        "ko-KR": {
          "label": "팝업 스토어",
          "description": "기간 한정 임시 매장"
        }
      }
    }
  ]'::jsonb,
  '{
    "category": "store_management",
    "displayOrder": 1,
    "updatable": true,
    "cacheable": true,
    "cacheExpireSeconds": 3600,
    "i18nEnabled": true,
    "supportedLocales": ["zh-CN", "en-US", "ja-JP", "ko-KR"]
  }'::jsonb
) ON CONFLICT (pid) DO UPDATE SET
  items = EXCLUDED.items,
  extension = EXCLUDED.extension,
  updated_at = now();

-- 2. 门店状态 (ds_storeStatuses) - 国际化版本
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
  1,
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
      "code": "active",
      "label": "营业中",
      "name": "营业中",
      "color": "success",
      "icon": "check-circle",
      "i18n": {
        "zh-CN": {
          "label": "营业中",
          "description": "门店正常营业"
        },
        "en-US": {
          "label": "Active",
          "description": "Store is operating normally"
        },
        "ja-JP": {
          "label": "営業中",
          "description": "店舗は正常に営業しています"
        },
        "ko-KR": {
          "label": "영업 중",
          "description": "매장이 정상적으로 운영 중입니다"
        }
      }
    },
    {
      "value": "inactive",
      "code": "inactive",
      "label": "暂停营业",
      "name": "暂停营业",
      "color": "warning",
      "icon": "pause-circle",
      "i18n": {
        "zh-CN": {
          "label": "暂停营业",
          "description": "门店临时暂停营业"
        },
        "en-US": {
          "label": "Inactive",
          "description": "Store is temporarily suspended"
        },
        "ja-JP": {
          "label": "営業停止中",
          "description": "店舗は一時的に営業を停止しています"
        },
        "ko-KR": {
          "label": "영업 정지",
          "description": "매장이 일시적으로 운영을 중단했습니다"
        }
      }
    },
    {
      "value": "preparing",
      "code": "preparing",
      "label": "筹备中",
      "name": "筹备中",
      "color": "info",
      "icon": "clock",
      "i18n": {
        "zh-CN": {
          "label": "筹备中",
          "description": "门店正在筹备，尚未开业"
        },
        "en-US": {
          "label": "Preparing",
          "description": "Store is under preparation, not yet opened"
        },
        "ja-JP": {
          "label": "準備中",
          "description": "店舗は準備中で、まだ開店していません"
        },
        "ko-KR": {
          "label": "준비 중",
          "description": "매장이 준비 중이며 아직 오픈하지 않았습니다"
        }
      }
    },
    {
      "value": "renovating",
      "code": "renovating",
      "label": "装修中",
      "name": "装修中",
      "color": "warning",
      "icon": "wrench",
      "i18n": {
        "zh-CN": {
          "label": "装修中",
          "description": "门店正在装修改造"
        },
        "en-US": {
          "label": "Renovating",
          "description": "Store is under renovation"
        },
        "ja-JP": {
          "label": "改装中",
          "description": "店舗は改装中です"
        },
        "ko-KR": {
          "label": "리노베이션 중",
          "description": "매장이 리노베이션 중입니다"
        }
      }
    },
    {
      "value": "closed",
      "code": "closed",
      "label": "已关闭",
      "name": "已关闭",
      "color": "error",
      "icon": "x-circle",
      "i18n": {
        "zh-CN": {
          "label": "已关闭",
          "description": "门店已永久关闭"
        },
        "en-US": {
          "label": "Closed",
          "description": "Store is permanently closed"
        },
        "ja-JP": {
          "label": "閉店済み",
          "description": "店舗は永久に閉店しました"
        },
        "ko-KR": {
          "label": "폐점",
          "description": "매장이 영구적으로 폐점했습니다"
        }
      }
    }
  ]'::jsonb,
  '{
    "category": "store_management",
    "displayOrder": 2,
    "updatable": true,
    "cacheable": true,
    "cacheExpireSeconds": 3600,
    "i18nEnabled": true,
    "supportedLocales": ["zh-CN", "en-US", "ja-JP", "ko-KR"],
    "statusFlow": ["preparing", "active", "inactive", "renovating", "closed"]
  }'::jsonb
) ON CONFLICT (pid) DO UPDATE SET
  items = EXCLUDED.items,
  extension = EXCLUDED.extension,
  updated_at = now();

-- ============================================
-- 查询国际化数据
-- ============================================

-- 查看门店类型的国际化标签
SELECT
  code,
  jsonb_array_elements(items) -> 'value' as value,
  jsonb_array_elements(items) -> 'i18n' -> 'zh-CN' -> 'label' as label_zh_cn,
  jsonb_array_elements(items) -> 'i18n' -> 'en-US' -> 'label' as label_en_us,
  jsonb_array_elements(items) -> 'i18n' -> 'ja-JP' -> 'label' as label_ja_jp,
  jsonb_array_elements(items) -> 'i18n' -> 'ko-KR' -> 'label' as label_ko_kr
FROM ab_data_source
WHERE code = 'ds_storeTypes'
  AND is_current = TRUE;

-- 查看门店状态的国际化标签
SELECT
  code,
  jsonb_array_elements(items) -> 'value' as value,
  jsonb_array_elements(items) -> 'i18n' -> 'zh-CN' -> 'label' as label_zh_cn,
  jsonb_array_elements(items) -> 'i18n' -> 'en-US' -> 'label' as label_en_us,
  jsonb_array_elements(items) -> 'i18n' -> 'ja-JP' -> 'label' as label_ja_jp,
  jsonb_array_elements(items) -> 'i18n' -> 'ko-KR' -> 'label' as label_ko_kr
FROM ab_data_source
WHERE code = 'ds_storeStatuses'
  AND is_current = TRUE;

-- 提取特定语言的数据 (示例: 英文)
CREATE OR REPLACE FUNCTION get_datasource_items_by_locale(
  p_code TEXT,
  p_locale TEXT DEFAULT 'zh-CN'
) RETURNS TABLE (
  value TEXT,
  label TEXT,
  description TEXT
) AS $$
BEGIN
  RETURN QUERY
  SELECT
    (item ->> 'value')::TEXT,
    (item -> 'i18n' -> p_locale ->> 'label')::TEXT,
    (item -> 'i18n' -> p_locale ->> 'description')::TEXT
  FROM ab_data_source,
       jsonb_array_elements(items) AS item
  WHERE code = p_code
    AND is_current = TRUE
    AND tenant_id = 1;
END;
$$ LANGUAGE plpgsql;

-- 使用示例
SELECT * FROM get_datasource_items_by_locale('ds_storeTypes', 'en-US');
SELECT * FROM get_datasource_items_by_locale('ds_storeStatuses', 'ja-JP');

-- ============================================
-- 验证数据完整性
-- ============================================

-- 检查是否所有 item 都包含完整的国际化数据
SELECT
  code,
  (item ->> 'value') as value,
  (item -> 'i18n' ? 'zh-CN') as has_zh_cn,
  (item -> 'i18n' ? 'en-US') as has_en_us,
  (item -> 'i18n' ? 'ja-JP') as has_ja_jp,
  (item -> 'i18n' ? 'ko-KR') as has_ko_kr
FROM ab_data_source,
     jsonb_array_elements(items) AS item
WHERE code IN ('ds_storeTypes', 'ds_storeStatuses')
  AND is_current = TRUE;
