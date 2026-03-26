/**
 * E2E Test Order — Model test configuration.
 *
 * Defines the e2et_order model config (with item/log children)
 * for use with ModelTestHelper.
 *
 * @since 6.1.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig, ChildModelTestConfig } from '../model-test-helper';

const ITEM_CONFIG: ChildModelTestConfig = {
  modelCode: 'e2et_order_item',
  pageKey: 'e2et-order-item',
  namespace: 'e2et',
  parentField: 'e2et_order_id',
  commands: {
    create: 'create_order_item',
    update: 'update_order_item',
    delete: 'delete_order_item',
  },
  defaultData: () => ({
    e2et_item_name: `Item ${uniqueId('I')}`,
    e2et_item_spec: 'spec_m',
    e2et_item_qty: 5,
    e2et_item_price: 20.0,
  }),
  deleteOperationType: 'delete',
};

const LOG_CONFIG: ChildModelTestConfig = {
  modelCode: 'e2et_order_log',
  pageKey: 'e2et-order-log',
  namespace: 'e2et',
  parentField: 'e2et_log_order_id',
  commands: {},
  defaultData: () => ({}),
};

export const E2ET_ORDER_CONFIG: ModelTestConfig = {
  modelCode: 'e2et_order',
  pageKey: 'e2et-order',
  namespace: 'e2et',
  commands: {
    create: 'create_order',
    update: 'update_order',
    delete: 'delete_order',
    submit: 'submit_order',
    approve: 'approve_order',
    reject: 'reject_order',
    complete: 'complete_order',
    cancel: 'cancel_order',
  },
  defaultData: () => ({
    e2et_order_title: `E2E Order ${uniqueId()}`,
    e2et_order_type: 'normal',
    e2et_order_urgent: false,
  }),
  deleteOperationType: 'delete',
  children: {
    item: ITEM_CONFIG,
    log: LOG_CONFIG,
  },
};
