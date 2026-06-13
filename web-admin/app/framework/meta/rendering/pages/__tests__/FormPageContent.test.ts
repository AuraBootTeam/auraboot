import { describe, expect, it } from 'vitest';
import {
  getFormFieldValueWithAlias,
  mergeLoadedRecordWithDirtyFields,
  normalizeCommandPayloadValue,
  normalizeLoadedFormValue,
  normalizeLoadedRecordForForm,
  shouldBypassFormSubmit,
  unwrapJsonLikeValue,
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

describe('JSON-like form values', () => {
  it('formats jsonb envelopes when loading an edit form', () => {
    const loaded = normalizeLoadedFormValue(
      {
        type: 'jsonb',
        value: '{"X-Codex-QA":"true"}',
        null: false,
      },
      'jsonb',
    );

    expect(loaded).toBe('{\n  "X-Codex-QA": "true"\n}');
  });

  it('unwraps nested jsonb envelopes created by older edit submissions', () => {
    const nestedEnvelope = {
      type: 'jsonb',
      value: '{"null": false, "type": "jsonb", "value": "{\\"maxRetries\\": 2}"}',
      null: false,
    };

    expect(unwrapJsonLikeValue(nestedEnvelope)).toEqual({ maxRetries: 2 });
    expect(normalizeLoadedFormValue(nestedEnvelope, 'jsonb')).toBe('{\n  "maxRetries": 2\n}');
  });

  it('submits jsonb envelopes as business JSON instead of wrapper objects', () => {
    const payload = normalizeCommandPayloadValue(
      {
        type: 'jsonb',
        value: '{"null": false, "type": "jsonb", "value": "{\\"X-Codex-QA\\": \\"true\\"}"}',
        null: false,
      },
      'jsonb',
    );

    expect(JSON.parse(payload)).toEqual({ 'X-Codex-QA': 'true' });
    expect(payload).not.toContain('"type":"jsonb"');
  });

  it('normalizes loaded records with json and jsonb field metadata', () => {
    const loadedRecord = normalizeLoadedRecordForForm(
      {
        pid: 'record-1',
        default_headers: {
          type: 'jsonb',
          value: '{"X-Codex-QA":"true"}',
          null: false,
        },
        retry_policy: '{"maxRetries":2}',
      },
      {
        default_headers: 'jsonb',
        retry_policy: 'json',
      },
    );

    expect(loadedRecord).toMatchObject({
      pid: 'record-1',
      default_headers: '{\n  "X-Codex-QA": "true"\n}',
      retry_policy: '{\n  "maxRetries": 2\n}',
    });
  });
});
