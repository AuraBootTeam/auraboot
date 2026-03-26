/**
 * E2E Test Payment — Model test configuration.
 *
 * Defines the e2et_payment model config for use with ModelTestHelper.
 * Covers BPM approval workflow and UPDATE_RECORD sideEffect testing.
 *
 * @since 6.2.0
 */

import { uniqueId } from '../../e2e/helpers';
import type { ModelTestConfig } from '../model-test-helper';

export const E2ET_PAYMENT_CONFIG: ModelTestConfig = {
  modelCode: 'e2et_payment',
  pageKey: 'e2et-payment',
  namespace: 'e2et',
  commands: {
    create: 'create_payment',
    submit: 'submit_payment',
    approve: 'approve_payment',
    reject: 'reject_payment',
    pay: 'pay_payment',
  },
  defaultData: () => ({
    e2et_pay_amount: 1000.0,
    e2et_pay_method: 'bank_transfer',
    e2et_pay_remark: `Payment ${uniqueId('P')}`,
  }),
};
