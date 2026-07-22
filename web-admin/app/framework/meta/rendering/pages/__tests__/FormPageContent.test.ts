import { describe, expect, it } from 'vitest';
import {
  buildFormCommandPayload,
  collectFormFieldDataTypes,
  getJsonFormValueError,
  getFormFieldValueWithAlias,
  mergeLoadedRecordWithDirtyFields,
  normalizeCommandPayloadValue,
  normalizeLoadedRecordResponseForForm,
  normalizeLoadedFormValue,
  normalizeLoadedRecordForForm,
  resolveAfterSubmitRedirect,
  resolveAsyncCommandDispatch,
  resolveEditRecordEndpoint,
  resolveFormBackLink,
  resolveFormSubmitEndpoint,
  resolveSubmitCommandCode,
  shouldBypassFormSubmit,
  shouldLoadMainRecordForForm,
  unwrapJsonLikeValue,
} from '../FormPageContent';

describe('resolveSubmitCommandCode (convention over configuration)', () => {
  const crud = {
    create: 'sc:create_showcase',
    update: 'sc:update_showcase',
    delete: 'sc:delete_showcase',
  };

  it('falls back to the model create command in new mode when no explicit command', () => {
    expect(resolveSubmitCommandCode(null, crud, false)).toBe('sc:create_showcase');
  });

  it('falls back to the model update command in edit mode when no explicit command', () => {
    expect(resolveSubmitCommandCode(null, crud, true)).toBe('sc:update_showcase');
  });

  it('lets an explicit command override the convention map (create mode)', () => {
    expect(resolveSubmitCommandCode('sc:register_showcase', crud, false)).toBe(
      'sc:register_showcase',
    );
  });

  it('lets an explicit command override the convention map (edit mode)', () => {
    expect(resolveSubmitCommandCode('custom:amend', crud, true)).toBe('custom:amend');
  });

  it('returns null for a pure-CRUD model so the caller uses the dynamic CRUD API', () => {
    expect(resolveSubmitCommandCode(null, undefined, false)).toBeNull();
    expect(resolveSubmitCommandCode(null, {}, true)).toBeNull();
  });

  it('returns null when the map lacks the needed operation (update missing in edit mode)', () => {
    expect(resolveSubmitCommandCode(null, { create: 'sc:create_showcase' }, true)).toBeNull();
  });
});

describe('shouldLoadMainRecordForForm', () => {
  it('loads singleton edit forms even when the route has no record pid', () => {
    expect(
      shouldLoadMainRecordForForm('', {
        recordSource: {
          mode: 'singleton',
          endpoint: '/api/tenant/info',
        },
      }),
    ).toBe(true);
  });

  it('keeps create forms without a record pid unloaded', () => {
    expect(shouldLoadMainRecordForForm('', {})).toBe(false);
  });
});

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
    expect(
      getFormFieldValueWithAlias({ ruleBinding: { bindingKind: 'DECISION_REF' } }, 'rule_binding'),
    ).toEqual({
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

describe('collectFormFieldDataTypes', () => {
  it('keeps schema-declared fields even before async model metadata provides data types', () => {
    expect(
      collectFormFieldDataTypes(
        [
          {
            blockType: 'form-section',
            fields: [
              { field: 'process_name' },
              { field: 'process_key' },
              { field: 'deployed_at' },
            ],
          },
        ],
        {
          status: { dataType: 'string' },
          version: { dataType: 'integer' },
        },
      ),
    ).toEqual({
      process_name: '',
      process_key: '',
      deployed_at: '',
      status: 'string',
      version: 'integer',
    });
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

describe('datetime form values', () => {
  it('submits native datetime-local values with a local timezone offset', () => {
    const payload = normalizeCommandPayloadValue('2026-06-01T00:00', 'datetime');

    expect(payload).toMatch(/^2026-06-01T00:00:00[+-]\d{2}:\d{2}$/);
  });

  it('keeps offset-aware datetime payloads unchanged', () => {
    expect(normalizeCommandPayloadValue('2026-06-01T00:00:00+08:00', 'datetime')).toBe(
      '2026-06-01T00:00:00+08:00',
    );
    expect(normalizeCommandPayloadValue('2026-05-31T16:00:00Z', 'datetime')).toBe(
      '2026-05-31T16:00:00Z',
    );
  });

  it('loads offset-aware datetimes as browser-compatible datetime-local values', () => {
    const source = '2026-06-01T00:00:30+08:00';
    const parsed = new Date(source);
    const pad = (value: number, size = 2) => String(value).padStart(size, '0');
    const expected =
      `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())}` +
      `T${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`;

    expect(normalizeLoadedFormValue(source, 'datetime')).toBe(expected);
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
          recordPid: 'Q1',
        },
      }),
    ).toBeNull();
    expect(resolveAsyncCommandDispatch({ recordPid: 'Q1' })).toBeNull();
  });
});

describe('resolveAfterSubmitRedirect', () => {
  it('uses async task final result data for task workbench redirects', () => {
    expect(
      resolveAfterSubmitRedirect(
        {
          extension: {
            afterSubmitRedirect: '/p/bom_conversion_task_pcba_workbench/view/{taskId}',
          },
        },
        'bom_start_conversion',
        {
          taskId: 'TASK-START-1',
          status: 'COMPLETED',
        },
        undefined,
      ),
    ).toBe('/p/bom_conversion_task_pcba_workbench/view/TASK-START-1');
  });

  it('keeps recordPid compatibility for immediate command responses', () => {
    expect(
      resolveAfterSubmitRedirect(
        {
          extension: {
            afterSubmitRedirect: '/p/bom_conversion_task_pcba_workbench/view/{recordPid}',
          },
        },
        'bom_start_conversion',
        {
          data: {
            recordPid: 'TASK-LEGACY-1',
          },
        },
        undefined,
      ),
    ).toBe('/p/bom_conversion_task_pcba_workbench/view/TASK-LEGACY-1');
  });
});

describe('resolveFormBackLink', () => {
  it('falls back to the model list page when the schema declares no back target', () => {
    expect(resolveFormBackLink({}, 'crm_account_common')).toBe('/p/crm_account_common');
  });

  it('sends a command-entry form back to the pageKey it declares', () => {
    // Regression: /p/bom_start_conversion/new linked back to /p/bom_start_conversion,
    // which derives the pageKey bom_start_conversion_list — a page that does not exist,
    // so the back link rendered an error instead of the conversion workbench.
    expect(
      resolveFormBackLink(
        { extension: { backTo: 'bom_conversion_task_pcba_workbench_list' } },
        'bom_start_conversion',
      ),
    ).toBe('/p/bom_conversion_task_pcba_workbench');
  });

  it('accepts an absolute path back target', () => {
    expect(resolveFormBackLink({ extension: { backTo: '/p/c/quote_console' } }, 'anything')).toBe(
      '/p/c/quote_console',
    );
  });

  it('drops the back link when the page declares it has nowhere to go back to', () => {
    // A singleton settings form reached straight from a top-level menu has no
    // parent page; rendering a link to a derived list page invents a dead end.
    expect(resolveFormBackLink({ extension: { backTo: 'none' } }, 'system_preferences_form')).toBe(
      null,
    );
  });

  it('ignores a blank back target', () => {
    expect(resolveFormBackLink({ extension: { backTo: '   ' } }, 'crm_account_common')).toBe(
      '/p/crm_account_common',
    );
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

  it('blocks json/jsonb form values that are not valid objects or arrays', () => {
    expect(getJsonFormValueError('{"yellow":50}', 'json', 'Alarm Thresholds')).toBeNull();
    expect(getJsonFormValueError('[{"code":"temp"}]', 'jsonb', 'TSL Schema')).toBeNull();
    expect(getJsonFormValueError('"scalar"', 'json', 'TSL Schema')).toBe(
      'TSL Schema must be a valid JSON object or array',
    );
    expect(getJsonFormValueError('{"yellow":', 'json', 'Alarm Thresholds')).toBe(
      'Alarm Thresholds must be a valid JSON object or array',
    );
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

  it('adds snake_case aliases for declared form fields when custom APIs return camelCase DTOs', () => {
    const loadedRecord = normalizeLoadedRecordForForm(
      {
        pid: 'process-1',
        processName: '长假审批',
        processKey: 'wd_leave_approval',
        deployedAt: '2026-07-05T10:30:00',
      },
      {
        process_name: 'string',
        process_key: 'string',
        deployed_at: 'datetime',
      },
    );

    expect(loadedRecord).toMatchObject({
      pid: 'process-1',
      processName: '长假审批',
      processKey: 'wd_leave_approval',
      deployedAt: '2026-07-05T10:30:00',
      process_name: '长假审批',
      process_key: 'wd_leave_approval',
      deployed_at: '2026-07-05T10:30:00',
    });
  });

  it('unwraps ApiResponse.data for singleton recordSource forms', () => {
    const loadedRecord = normalizeLoadedRecordResponseForForm(
      {
        code: '0',
        message: 'OK',
        data: {
          datetimeFormat: 'YYYY-MM-DD HH:mm:ss',
          timezone: 'Asia/Shanghai',
          timezoneStatusText: '尚未配置租户默认时区，保存后将作为租户默认值。',
        },
      },
      {},
    );

    expect(loadedRecord).toMatchObject({
      datetimeFormat: 'YYYY-MM-DD HH:mm:ss',
      timezone: 'Asia/Shanghai',
      timezoneStatusText: '尚未配置租户默认时区，保存后将作为租户默认值。',
    });
    expect(loadedRecord).not.toHaveProperty('code');
    expect(loadedRecord).not.toHaveProperty('message');
  });
});

describe('resolveEditRecordEndpoint', () => {
  it('defaults to the generic dynamic endpoint when no recordSource', () => {
    expect(resolveEditRecordEndpoint(undefined, 'crm_lead', 'r1')).toBe('/api/dynamic/crm_lead/r1');
    expect(resolveEditRecordEndpoint({}, 'crm_lead', 'r1')).toBe('/api/dynamic/crm_lead/r1');
  });
  it('uses the custom endpoint and interpolates public pid placeholders', () => {
    expect(
      resolveEditRecordEndpoint(
        { recordSource: { endpoint: '/api/qr/{recordPid}' } },
        'qr_code',
        'abc',
      ),
    ).toBe('/api/qr/abc');
    expect(
      resolveEditRecordEndpoint(
        { recordSource: { endpoint: '/api/qr/${recordPid}' } },
        'qr_code',
        'abc',
      ),
    ).toBe('/api/qr/abc');
    expect(
      resolveEditRecordEndpoint({ recordSource: { endpoint: '/api/qr/{pid}' } }, 'qr_code', 'abc'),
    ).toBe('/api/qr/abc');
  });
  it('keeps legacy recordPid placeholder compatibility', () => {
    expect(
      resolveEditRecordEndpoint(
        { recordSource: { endpoint: '/api/qr/{recordPid}' } },
        'qr_code',
        'abc',
      ),
    ).toBe('/api/qr/abc');
    expect(
      resolveEditRecordEndpoint(
        { recordSource: { endpoint: '/api/qr/${recordPid}' } },
        'qr_code',
        'abc',
      ),
    ).toBe('/api/qr/abc');
  });
  it('url-encodes the public record pid', () => {
    expect(
      resolveEditRecordEndpoint(
        { recordSource: { endpoint: '/api/qr/{recordPid}' } },
        'qr_code',
        'a/b',
      ),
    ).toBe('/api/qr/a%2Fb');
  });

  it('allows singleton custom endpoints without a route record pid', () => {
    expect(
      resolveEditRecordEndpoint(
        { recordSource: { endpoint: '/api/tenant/info' } },
        'tenant_profile',
      ),
    ).toBe('/api/tenant/info');
  });

  it('allows extension recordSource endpoints when the import DTO drops top-level fields', () => {
    expect(
      resolveEditRecordEndpoint(
        { extension: { recordSource: { endpoint: '/api/tenant/info' } } },
        'tenant_profile',
      ),
    ).toBe('/api/tenant/info');
  });

  it('supports recordSource stored under extension for imported singleton pages', () => {
    expect(
      resolveEditRecordEndpoint(
        { extension: { recordSource: { endpoint: '/api/admin/system-preferences' } } },
        'system_preferences_form',
      ),
    ).toBe('/api/admin/system-preferences');
  });

  it('supports BPM process definition edit forms backed by the BPM API', () => {
    expect(
      resolveEditRecordEndpoint(
        {
          extension: {
            recordSource: {
              endpoint: '/api/bpm/process-definitions/{pid}',
              method: 'get',
            },
          },
        },
        'bpm_process_management',
        'process-1',
      ),
    ).toBe('/api/bpm/process-definitions/process-1');
  });
});

describe('resolveFormSubmitEndpoint', () => {
  it('uses a configured API submit endpoint and resolves pid from loaded form data', () => {
    expect(
      resolveFormSubmitEndpoint(
        {
          extension: {
            submitEndpoint: { type: 'api', method: 'put', endpoint: '/api/tenant/{pid}' },
          },
        },
        null,
        { pid: 'tenant-1' },
      ),
    ).toEqual({ endpoint: '/api/tenant/tenant-1', method: 'put' });
  });

  it('url-encodes route record pids for submit endpoints', () => {
    expect(
      resolveFormSubmitEndpoint(
        {
          extension: {
            submitEndpoint: { type: 'api', method: 'patch', endpoint: '/api/custom/${recordPid}' },
          },
        },
        'a/b',
        {},
      ),
    ).toEqual({ endpoint: '/api/custom/a%2Fb', method: 'patch' });
  });
});
