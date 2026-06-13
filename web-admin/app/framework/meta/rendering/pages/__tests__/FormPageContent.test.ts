import { describe, expect, it } from 'vitest';
import {
  getFormFieldValueWithAlias,
  mergeLoadedRecordWithDirtyFields,
  shouldBypassFormSubmit,
} from '../FormPageContent';

describe('mergeLoadedRecordWithDirtyFields', () => {
  it('keeps user-edited fields when a late edit-record fetch resolves', () => {
    const loadedRecord = {
      pid: 'record-1',
      templateVersion: 'REV-1',
      structureRule: '{"root":"PCBA"}',
      enabled: true,
    };
    const currentData = {
      pid: 'record-1',
      templateVersion: 'REV-2',
      structureRule: '{"root":"PCBA"}',
      enabled: true,
    };

    expect(
      mergeLoadedRecordWithDirtyFields(loadedRecord, currentData, new Set(['templateVersion'])),
    ).toEqual({
      pid: 'record-1',
      templateVersion: 'REV-2',
      structureRule: '{"root":"PCBA"}',
      enabled: true,
    });
  });

  it('uses loaded data unchanged when no field has been edited', () => {
    const loadedRecord = { pid: 'record-1', revision: 'A0' };

    expect(mergeLoadedRecordWithDirtyFields(loadedRecord, { revision: 'A1' }, new Set())).toBe(
      loadedRecord,
    );
  });
});

describe('getFormFieldValueWithAlias', () => {
  it('reads camelCase backend fields for snake_case DSL value fields', () => {
    expect(getFormFieldValueWithAlias({ ruleBinding: { bindingKind: 'DECISION_REF' } }, 'rule_binding')).toEqual({
      bindingKind: 'DECISION_REF',
    });
  });

  it('keeps the exact field value when both exact and alias keys exist', () => {
    expect(
      getFormFieldValueWithAlias(
        {
          ruleBinding: { bindingKind: 'ALIAS' },
          rule_binding: { bindingKind: 'EXACT' },
        },
        'rule_binding',
      ),
    ).toEqual({ bindingKind: 'EXACT' });
  });
});

describe('shouldBypassFormSubmit', () => {
  it('treats refresh as a non-submit form button action', () => {
    expect(shouldBypassFormSubmit({ code: 'refresh' }, '')).toBe(true);
    expect(shouldBypassFormSubmit({ code: 'reload', action: 'refresh' }, 'refresh')).toBe(true);
  });
});
