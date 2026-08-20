import { describe, expect, it } from 'vitest';

import {
  canUseImport,
  resolveImportExecutionMessage,
  resolveImportFieldLabel,
  resolveImportMessageFieldCodes,
  resolveImportReferenceMessage,
} from '../importCapability';

describe('resolveImportExecutionMessage', () => {
  it('maps the stable backend row-write failure to actionable copy', () => {
    expect(
      resolveImportExecutionMessage(
        'Import row could not be saved. Check the field values and try again.',
      ),
    ).toEqual({
      key: 'import.validation.row_write_failed',
      fallback: '该行无法保存，请检查字段值与模板要求后重试',
    });
  });

  it('localizes update match failures without leaking internal field codes', () => {
    expect(
      resolveImportExecutionMessage('No existing record matches crm_lead_code=MISSING-001', {
        crm_lead_code: '线索编号',
      }),
    ).toEqual({
      key: 'import.validation.update_record_missing',
      params: { field: '线索编号' },
      fallback: '未找到与“线索编号”匹配的现有记录，请修正匹配值后重试',
    });
    expect(
      resolveImportExecutionMessage('Import match key is not unique: crm_lead_code=LEAD-001', {
        crm_lead_code: '线索编号',
      }),
    ).toEqual({
      key: 'import.validation.update_match_ambiguous',
      params: { field: '线索编号' },
      fallback: '匹配字段“线索编号”对应多条记录，请改用唯一业务值后重试',
    });
  });

  it('does not reinterpret unrelated business errors', () => {
    expect(resolveImportExecutionMessage('Duplicate code')).toBeNull();
  });
});

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

describe('resolveImportReferenceMessage', () => {
  it('localizes a missing or inaccessible business-key reference with diagnostic suffixes', () => {
    expect(
      resolveImportReferenceMessage(
        "Referenced record does not exist or is not accessible for 所属客户: 'X' (accepted: crm_acc_code)",
      ),
    ).toBe('关联记录不存在或无权访问');
  });

  it('explains how to disambiguate a non-unique reference', () => {
    expect(
      resolveImportReferenceMessage(
        "Reference value is ambiguous for 所属客户: '同名客户' matched multiple records",
      ),
    ).toBe('关联值不唯一，请改用唯一业务编码或 PID');
  });

  it('leaves unrelated backend failures to the generic resolver', () => {
    expect(resolveImportReferenceMessage('Import task failed')).toBeNull();
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
