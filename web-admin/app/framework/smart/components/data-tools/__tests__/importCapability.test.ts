import { describe, expect, it } from 'vitest';

import {
  canUseImport,
  resolveImportFieldLabel,
  resolveImportMessageFieldCodes,
} from '../importCapability';

describe('canUseImport', () => {
  it('fails closed when the page DSL does not explicitly enable import', () => {
    expect(canUseImport(undefined, () => true)).toBe(false);
    expect(canUseImport({}, () => true)).toBe(false);
  });

  it('fails closed when an enabled page omits the backend permission declaration', () => {
    expect(canUseImport({ enabled: true }, () => true)).toBe(false);
  });

  it('enforces the declared model import permission', () => {
    const config = { enabled: true, permissionCode: 'model.crm_account_common.import' };

    expect(canUseImport(config, () => false)).toBe(false);
    expect(canUseImport(config, (permission) => permission === config.permissionCode)).toBe(true);
  });
});

describe('resolveImportFieldLabel', () => {
  const labels = { crm_acc_code: '客户编号', crm_acc_name: '客户名称' };

  it('replaces internal field codes with business labels', () => {
    expect(resolveImportFieldLabel('crm_acc_code', labels)).toBe('客户编号');
    expect(resolveImportFieldLabel('* crm_acc_name', labels)).toBe('* 客户名称');
  });

  it('keeps an already friendly or unknown header readable', () => {
    expect(resolveImportFieldLabel('客户名称', labels)).toBe('客户名称');
  });
});

describe('resolveImportMessageFieldCodes', () => {
  const labels = { crm_acc_name: '客户名称', crm_acc_status: '客户状态' };

  it('removes internal field codes from backend command errors', () => {
    expect(resolveImportMessageFieldCodes("Field 'crm_acc_status' is required", labels)).toBe(
      "Field '客户状态' is required",
    );
  });

  it('preserves messages that contain no known field code', () => {
    expect(resolveImportMessageFieldCodes('Import task failed', labels)).toBe('Import task failed');
  });
});
