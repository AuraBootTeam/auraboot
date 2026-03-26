/**
 * Sales Pricing — Model test configurations for Price List and Discount Rule.
 *
 * Provides ModelTestConfig instances for:
 *   - sl_price_list: price list lifecycle (draft → active → inactive → archived)
 *   - sl_discount_rule: discount rule lifecycle (draft → active → inactive → archived)
 *
 * @since 10.0.0
 */

import { uniqueId, todayStr, dateOffsetStr } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

// ---------------------------------------------------------------------------
// Price List Config
// ---------------------------------------------------------------------------

export const SL_PRICE_LIST_CONFIG: ModelTestConfig = {
  modelCode: 'sl_price_list',
  pageKey: 'sl-price-list',
  namespace: 'sl',
  commands: {
    create: 'create_price_list',
    update: 'update_price_list',
    delete: 'delete_price_list',
    activate: 'activate_price_list',
    deactivate: 'deactivate_price_list',
    archive: 'archive_price_list',
  },
  defaultData: () => ({
    sl_pl_name: `E2E Price List ${uniqueId('PL')}`,
    sl_pl_code: `PL-${uniqueId()}`,
    sl_pl_currency: 'cny',
    sl_pl_priority: 10,
    sl_pl_status: 'draft',
    sl_pl_valid_from: todayStr(),
    sl_pl_valid_to: dateOffsetStr(365),
    sl_pl_description: 'E2E test price list',
  }),
  deleteOperationType: 'delete',
};

// ---------------------------------------------------------------------------
// Discount Rule Config
// ---------------------------------------------------------------------------

export const SL_DISCOUNT_RULE_CONFIG: ModelTestConfig = {
  modelCode: 'sl_discount_rule',
  pageKey: 'sl-discount-rule',
  namespace: 'sl',
  commands: {
    create: 'create_discount_rule',
    update: 'update_discount_rule',
    delete: 'delete_discount_rule',
    activate: 'activate_discount_rule',
    deactivate: 'deactivate_discount_rule',
    archive: 'archive_discount_rule',
  },
  defaultData: () => ({
    sl_dr_name: `E2E Discount Rule ${uniqueId('DR')}`,
    sl_dr_code: `DR-${uniqueId()}`,
    sl_dr_type: 'percentage',
    sl_dr_value: 10,
    sl_dr_status: 'draft',
    sl_dr_min_qty: 1,
    sl_dr_valid_from: todayStr(),
    sl_dr_valid_to: dateOffsetStr(365),
    sl_dr_description: 'E2E test discount rule',
  }),
  deleteOperationType: 'delete',
};
