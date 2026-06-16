import { describe, expect, it } from 'vitest';
import {
  buildFormCommandPayload,
  getFormFieldValueWithAlias,
  mergeLoadedRecordWithDirtyFields,
  normalizeCommandPayloadValue,
  normalizeLoadedFormValue,
  normalizeLoadedRecordForForm,
  resolveAsyncCommandDispatch,
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

describe('buildFormCommandPayload', () => {
  it('keeps ordinary command payloads limited to model-backed fields', () => {
    const payload = buildFormCommandPayload(
      {
        qo_quote_crm_account_id: 'ACC1',
        unknown_upload_slot: 'FILE-1',
      },
      {
        qo_quote_crm_account_id: { dataType: 'reference' },
      },
      [
        {
          blockType: 'form-section',
          fields: [
            { field: 'qo_quote_crm_account_id' },
            { field: 'unknown_upload_slot', dataType: 'file' },
          ],
        },
      ],
    );

    expect(payload).toEqual({ qo_quote_crm_account_id: 'ACC1' });
  });

  it('includes transient submitPayload fields using their raw DSL data type', () => {
    const fileValue = [
      {
        name: 'customer-gerber.zip',
        status: 'done',
        response: { fileId: 'FILE-GERBER-1' },
        size: 1024,
        type: 'application/zip',
      },
    ];

    const payload = buildFormCommandPayload(
      {
        qo_quote_crm_account_id: 'ACC1',
        gerber_source_file: fileValue,
        cpl_source_file: [],
        local_only_note: 'not submitted',
      },
      {
        qo_quote_crm_account_id: { dataType: 'reference' },
      },
      [
        {
          blockType: 'form-section',
          fields: [
            { field: 'qo_quote_crm_account_id' },
            { field: 'gerber_source_file', dataType: 'file', transient: true, submitPayload: true },
            { field: 'cpl_source_file', dataType: 'file', transient: true, submitPayload: true },
            { field: 'local_only_note', dataType: 'string', transient: true },
          ],
        },
      ],
    );

    expect(payload.qo_quote_crm_account_id).toBe('ACC1');
    expect(JSON.parse(payload.gerber_source_file)).toEqual([
      expect.objectContaining({
        name: 'customer-gerber.zip',
        fileId: 'FILE-GERBER-1',
      }),
    ]);
    expect(payload).not.toHaveProperty('cpl_source_file');
    expect(payload).not.toHaveProperty('local_only_note');
  });
});

describe('resolveAsyncCommandDispatch', () => {
  it('detects command-engine async dispatch payloads nested under data', () => {
    expect(
      resolveAsyncCommandDispatch({
        commandCode: 'qo_quote_common:create',
        data: {
          async: true,
          taskCode: ' TASK-1 ',
        },
      }),
    ).toEqual({ taskCode: 'TASK-1' });
  });

  it('ignores regular synchronous command results', () => {
    expect(
      resolveAsyncCommandDispatch({
        commandCode: 'qo_quote_common:create',
        data: {
          recordId: 'Q1',
        },
      }),
    ).toBeNull();
    expect(resolveAsyncCommandDispatch({ recordId: 'Q1' })).toBeNull();
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
