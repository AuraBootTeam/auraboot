import { startProcessFromAction } from '~/plugins/core-bpm/services/bpmWorkbenchService';
import {
  queryBuilderService,
  type QueryBuilderRequest,
} from '~/plugins/core-designer/components/query-builder/services/queryBuilderService';
import { commandActionService } from '~/plugins/core-designer/components/studio/services/command/CommandActionService';
import {
  namedQueryService,
  type NamedQueryExecuteRequest,
} from '~/shared/services/namedQueryService';
import type { DslBlockV3, PageSchemaV3 } from '../types';

const RESERVED_AUDIT_CONTEXT_KEY = '__auditContext';
const EXACT_TEMPLATE_PATTERN = /^\{\{\s*([a-zA-Z0-9_.]+)\s*\}\}$/;
const TEMPLATE_TOKEN_PATTERN = /\{\{\s*([a-zA-Z0-9_.]+)\s*\}\}/g;

export interface RuntimeWidgetData {
  source?: string;
  value?: string;
  series?: Array<{ label: string; value: number }>;
  columns?: string[];
  rows?: string[][];
  emptyText?: string;
}

export interface RuntimeHelperBlockData {
  source?: string;
  description?: string;
  feedback?: string;
  suggestedFields?: Array<{ field: string; label: string; value: string }>;
  status?: string;
  assignee?: string;
  dueAt?: string;
  actions?: Array<{ label: string; actionType: string }>;
  items?: Array<{ actor: string; action: string; time: string; description: string }>;
  entries?: Array<{ field: string; from: string; to: string; changedBy: string }>;
  emptyText?: string;
}

export interface RuntimePickerOption {
  label: string;
  value: string;
  record?: Record<string, unknown>;
}

export interface RuntimeActionResult {
  status: string;
  payload?: Record<string, unknown>;
}

export interface RuntimeExecutionContext {
  source: 'unified-designer-runtime-preview' | 'runtime';
  pageId: string;
  pageKind: PageSchemaV3['kind'];
  schemaVersion: number;
  blockId: string;
  blockType: string;
  blockPath: string[];
  actionType?: string;
  permissionCode?: string;
  widgetType?: string;
  routeQuery?: Record<string, string | string[]>;
  formValues?: Record<string, unknown>;
  selectedRows?: Array<Record<string, unknown>>;
  selectedRowIds?: string[];
  currentRow?: Record<string, unknown>;
  currentRowId?: string;
  pickerSearch?: string;
}

export type RuntimeExecutionErrorKind =
  | 'auth'
  | 'permission'
  | 'not-found'
  | 'validation'
  | 'configuration'
  | 'network'
  | 'timeout'
  | 'server'
  | 'unknown';

export interface RuntimeExecutionIssue {
  kind: RuntimeExecutionErrorKind;
  message: string;
  code?: string;
  hint?: string;
  context?: Record<string, unknown> | null;
}

export class RuntimeExecutionError extends Error implements RuntimeExecutionIssue {
  kind: RuntimeExecutionErrorKind;
  code?: string;
  hint?: string;
  context?: Record<string, unknown> | null;

  constructor(issue: RuntimeExecutionIssue) {
    super(issue.message);
    this.name = 'RuntimeExecutionError';
    this.kind = issue.kind;
    this.code = issue.code;
    this.hint = issue.hint;
    this.context = issue.context;
  }
}

export interface RuntimeExecutionServices {
  loadWidgetData?: (
    block: DslBlockV3,
    context?: RuntimeExecutionContext,
  ) => Promise<RuntimeWidgetData | null>;
  loadPickerOptions?: (
    block: DslBlockV3,
    context?: RuntimeExecutionContext,
  ) => Promise<RuntimePickerOption[]>;
  loadHelperBlockData?: (
    block: DslBlockV3,
    context?: RuntimeExecutionContext,
  ) => Promise<RuntimeHelperBlockData | null>;
  executeAction?: (
    block: DslBlockV3,
    context?: RuntimeExecutionContext,
  ) => Promise<RuntimeActionResult>;
}

export const defaultRuntimeExecutionServices: RuntimeExecutionServices = {
  loadWidgetData: executeWidgetDataSource,
  loadPickerOptions: executePickerOptions,
  loadHelperBlockData: executeHelperBlockDataSource,
  executeAction: executeRuntimeAction,
};

interface WidgetDataShape {
  fields?: string[];
  groupBy?: string[];
  aggregations?: QueryBuilderRequest['aggregations'];
}

interface NamedQueryRuntimeRequest {
  code: string;
  request: NamedQueryExecuteRequest;
  shape: WidgetDataShape;
}

interface PickerDataSourceRequest {
  mode: 'model' | 'named-query';
  source: string;
  valueField: string;
  displayField: string;
  pageSize: number;
  parameters?: Record<string, unknown>;
  searchKeyword?: string;
  searchField: string;
  searchParameter: string;
}

async function executeWidgetDataSource(block: DslBlockV3): Promise<RuntimeWidgetData | null> {
  const namedQuery = normalizeNamedQueryRequest(block);
  if (namedQuery) {
    const result = await namedQueryService.execute(namedQuery.code, namedQuery.request);
    return mapQueryRowsToWidgetData(block, result.records ?? [], namedQuery.shape, 'named-query');
  }

  const query = normalizeQueryRequest(block);
  if (!query) return null;

  const result = await queryBuilderService.execute(query);
  if (!isSuccessCode(result.code)) {
    throw createRuntimeExecutionError(
      {
        code: result.code,
        message: result.message || result.desc,
        context: result.context,
      },
      'Query execution failed',
    );
  }

  return mapQueryRowsToWidgetData(block, result.data ?? [], query, 'query-builder');
}

async function executeHelperBlockDataSource(
  block: DslBlockV3,
): Promise<RuntimeHelperBlockData | null> {
  const namedQuery = normalizeNamedQueryRequest(block);
  if (namedQuery) {
    const result = await namedQueryService.execute(namedQuery.code, namedQuery.request);
    return mapRowsToHelperBlockData(block, result.records ?? [], 'named-query');
  }

  const query = normalizeQueryRequest(block);
  if (!query) return null;

  const result = await queryBuilderService.execute(query);
  if (!isSuccessCode(result.code)) {
    throw createRuntimeExecutionError(
      {
        code: result.code,
        message: result.message || result.desc,
        context: result.context,
      },
      'Helper block query failed',
    );
  }

  return mapRowsToHelperBlockData(block, result.data ?? [], 'query-builder');
}

async function executePickerOptions(
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): Promise<RuntimePickerOption[]> {
  const request = normalizePickerDataSourceRequest(block, context);
  if (!request) return [];

  if (request.mode === 'named-query') {
    const parameters = request.searchKeyword
      ? {
          ...(request.parameters ?? {}),
          [request.searchParameter]: request.searchKeyword,
        }
      : request.parameters;
    const namedQueryRequest: NamedQueryExecuteRequest = {
      page: 1,
      size: request.pageSize,
      executeQuery: true,
      ...(parameters ? { parameters } : {}),
    };
    if (request.searchKeyword) {
      namedQueryRequest.whereConditions = [
        {
          field: request.searchField,
          operator: 'contains',
          value: request.searchKeyword,
        },
      ];
    }
    const result = await namedQueryService.execute(request.source, namedQueryRequest);
    return mapRowsToPickerOptions(result.records ?? [], request);
  }

  const queryRequest: QueryBuilderRequest = {
    modelCode: request.source,
    fields: uniqueStrings([request.valueField, request.displayField]),
    limit: request.pageSize,
  };
  if (request.searchKeyword) {
    queryRequest.filters = [
      {
        fieldName: request.searchField,
        operator: 'LIKE',
        value: request.searchKeyword,
      },
    ];
  }

  const result = await queryBuilderService.execute(queryRequest);
  if (!isSuccessCode(result.code)) {
    throw createRuntimeExecutionError(
      {
        code: result.code,
        message: result.message || result.desc,
        context: result.context,
      },
      'Picker query failed',
    );
  }

  return mapRowsToPickerOptions(result.data ?? [], request);
}

async function executeRuntimeAction(
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): Promise<RuntimeActionResult> {
  const actionType = block.actionType || 'command';

  if (actionType === 'command') {
    const commandCode = getStringProp(block.props?.command);
    if (!commandCode) throw new Error('Command is required');

    const result = await commandActionService.execute(
      commandCode,
      sanitizeRuntimeActionPayload(getRecordProp(block.props?.payload), block, context),
      {
        clientRequestId: getStringProp(block.props?.clientRequestId) || undefined,
        targetRecordPid: getStringProp(block.props?.targetRecordPid) || undefined,
        targetRecordId: getStringProp(block.props?.targetRecordId) || undefined,
        operationType: getOperationType(block.props?.operationType),
        auditContext: createRuntimeAuditContext(block, context),
      },
    );

    return {
      status:
        getStringProp(block.props?.feedback) ||
        `Command executed: ${result.commandCode || commandCode}`,
      payload: result.data,
    };
  }

  if (actionType === 'workflow') {
    const workflowKey =
      getStringProp(block.props?.workflowKey) || getStringProp(block.props?.processDefinitionKey);
    if (!workflowKey) throw new Error('Workflow key is required');

    const workflowVariables =
      getRecordPropOrUndefined(block.props?.variables) ??
      getRecordPropOrUndefined(block.props?.payload);
    const configuredBusinessKey = getStringProp(block.props?.businessKey);

    const result = await startProcessFromAction({
      processDefinitionKey: workflowKey,
      businessKey:
        (configuredBusinessKey
          ? resolveRuntimeTemplateString(configuredBusinessKey, block, context)
          : '') || `runtime-action-${block.id}-${Date.now()}`,
      variables: workflowVariables
        ? sanitizeRuntimeActionPayload(workflowVariables, block, context)
        : undefined,
    });

    return {
      status:
        getStringProp(block.props?.feedback) ||
        `Workflow started: ${result.processInstanceId}`,
    };
  }

  return {
    status: getStringProp(block.props?.feedback) || `${actionType} executed`,
  };
}

function createRuntimeAuditContext(
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): Record<string, unknown> {
  return {
    source: context?.source ?? 'runtime',
    pageId: context?.pageId,
    pageKind: context?.pageKind,
    schemaVersion: context?.schemaVersion,
    blockId: context?.blockId ?? block.id,
    blockType: context?.blockType ?? block.blockType,
    blockPath: context?.blockPath ?? [block.id],
    actionType: context?.actionType ?? block.actionType,
    permissionCode: context?.permissionCode,
    widgetType: context?.widgetType ?? block.widgetType,
  };
}

export function normalizeRuntimeExecutionError(
  error: unknown,
  fallback = 'Runtime execution failed',
): RuntimeExecutionIssue {
  if (error instanceof RuntimeExecutionError) {
    return {
      kind: error.kind,
      message: error.message || fallback,
      code: error.code,
      hint: error.hint,
      context: error.context,
    };
  }

  if (isRecord(error)) {
    const message =
      getStringProp(error.message) ||
      getStringProp(error.desc) ||
      getStringProp(error.error) ||
      fallback;
    return buildRuntimeExecutionIssue({
      code: getStringProp(error.code) || getStringProp(error.status),
      message,
      context: getRecordPropOrUndefined(error.context) ?? null,
    });
  }

  if (error instanceof Error) {
    return buildRuntimeExecutionIssue({
      message: error.message || fallback,
    });
  }

  return buildRuntimeExecutionIssue({
    message: typeof error === 'string' && error ? error : fallback,
  });
}

function createRuntimeExecutionError(
  input: { code?: unknown; message?: string; context?: Record<string, unknown> | null },
  fallback: string,
): RuntimeExecutionError {
  return new RuntimeExecutionError(
    buildRuntimeExecutionIssue({
      code: typeof input.code === 'string' || typeof input.code === 'number' ? String(input.code) : '',
      message: input.message || fallback,
      context: input.context ?? null,
    }),
  );
}

function buildRuntimeExecutionIssue(input: {
  code?: string;
  message: string;
  context?: Record<string, unknown> | null;
}): RuntimeExecutionIssue {
  const code = input.code || extractStatusCode(input.message);
  const kind = classifyRuntimeExecutionError(code, input.message);
  return {
    kind,
    code: code || undefined,
    message: input.message,
    hint: getRuntimeErrorHint(kind),
    context: input.context ?? null,
  };
}

function classifyRuntimeExecutionError(
  code: string | undefined,
  message: string,
): RuntimeExecutionErrorKind {
  const normalizedCode = code ? String(code) : '';
  const text = message.toLowerCase();

  if (normalizedCode === '401' || normalizedCode === '10401' || /unauthorized|not authenticated/.test(text)) {
    return 'auth';
  }
  if (
    normalizedCode === '403' ||
    normalizedCode === '10403' ||
    /access forbidden|access denied|forbidden|permission|not allowed/.test(text)
  ) {
    return 'permission';
  }
  if (normalizedCode === '404' || /not found|missing command|missing query/.test(text)) {
    return 'not-found';
  }
  if (
    normalizedCode === '400' ||
    normalizedCode === '422' ||
    /validation|required|invalid|bad parameter/.test(text)
  ) {
    return 'validation';
  }
  if (/command is required|workflow key is required|widget .* requires/.test(text)) {
    return 'configuration';
  }
  if (normalizedCode === 'network_error' || /network error|failed to fetch/.test(text)) {
    return 'network';
  }
  if (normalizedCode === 'timeout_error' || /timeout|timed out/.test(text)) {
    return 'timeout';
  }
  if (/^5\d\d$/.test(normalizedCode) || /internal server error|unexpected system exception/.test(text)) {
    return 'server';
  }
  return 'unknown';
}

function getRuntimeErrorHint(kind: RuntimeExecutionErrorKind): string | undefined {
  if (kind === 'auth') return 'Sign in again, then rerun the preview.';
  if (kind === 'permission') return 'Check the permission required by this block.';
  if (kind === 'not-found') return 'Verify the configured code still exists.';
  if (kind === 'validation') return 'Review the block configuration and request payload.';
  if (kind === 'configuration') return 'Fill in the required runtime configuration.';
  if (kind === 'network') return 'Check the local backend and BFF connection.';
  if (kind === 'timeout') return 'Retry after the backend finishes or reduce query scope.';
  if (kind === 'server') return 'Check backend logs for the failing runtime request.';
  return undefined;
}

function extractStatusCode(message: string): string {
  const match = message.match(/\b(401|403|404|422|500|502|503)\b/);
  return match?.[1] ?? '';
}

function normalizeQueryRequest(block: DslBlockV3): QueryBuilderRequest | null {
  const dataSource = block.dataSource;
  if (!dataSource || !isRecord(dataSource.query)) return null;

  const query = dataSource.query;
  const modelCode =
    getStringProp(query.modelCode) ||
    getStringProp(dataSource.modelCode) ||
    getStringProp(dataSource.model);
  if (!modelCode) throw new Error('Widget query requires modelCode');

  const request: QueryBuilderRequest = { modelCode };
  const fields = getStringArray(query.fields);
  const groupBy = getStringArray(query.groupBy);
  const sortField = getStringProp(query.sortField);
  const sortOrder = query.sortOrder === 'asc' || query.sortOrder === 'desc' ? query.sortOrder : '';
  const limit = getFiniteNumber(query.limit);

  if (fields.length) request.fields = fields;
  if (Array.isArray(query.filters)) request.filters = query.filters as QueryBuilderRequest['filters'];
  if (groupBy.length) request.groupBy = groupBy;
  if (Array.isArray(query.aggregations)) {
    request.aggregations = query.aggregations as QueryBuilderRequest['aggregations'];
  }
  if (sortField) request.sortField = sortField;
  if (sortOrder) request.sortOrder = sortOrder;
  if (typeof limit === 'number') request.limit = limit;

  return request;
}

function normalizeNamedQueryRequest(block: DslBlockV3): NamedQueryRuntimeRequest | null {
  const dataSource = block.dataSource;
  if (!dataSource) return null;

  const dataSourceType = getStringProp(dataSource.type) || getStringProp(dataSource.mode);
  const queryCode =
    getStringProp(dataSource.queryCode) ||
    getStringProp(dataSource.namedQueryCode) ||
    getStringProp(dataSource.namedQuery) ||
    getStringProp(dataSource.code);
  const isNamedQuery =
    dataSourceType === 'namedQuery' || dataSourceType === 'named-query' || Boolean(queryCode);
  if (!isNamedQuery) return null;
  if (!queryCode) throw new Error('Widget named query requires queryCode');

  const request: NamedQueryExecuteRequest = {
    page:
      getPositiveInteger(dataSource.page) ??
      getPositiveInteger(dataSource.pageNum) ??
      1,
    size:
      getPositiveInteger(dataSource.size) ??
      getPositiveInteger(dataSource.pageSize) ??
      getPositiveInteger(dataSource.limit) ??
      20,
    executeQuery: true,
  };
  const parameters =
    getRecordPropOrUndefined(dataSource.parameters) ?? getRecordPropOrUndefined(dataSource.params);
  if (parameters) request.parameters = parameters;
  if (dataSource.whereConditions !== undefined) request.whereConditions = dataSource.whereConditions;
  if (dataSource.orderConditions !== undefined) request.orderConditions = dataSource.orderConditions;
  const timeoutSeconds = getPositiveInteger(dataSource.timeoutSeconds);
  if (timeoutSeconds) request.timeoutSeconds = timeoutSeconds;

  return {
    code: queryCode,
    request,
    shape: {
      fields: getStringArray(dataSource.fields),
      groupBy: getStringArray(dataSource.groupBy),
    },
  };
}

function normalizePickerDataSourceRequest(
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): PickerDataSourceRequest | null {
  const props = block.props ?? {};
  const mode = normalizePickerDataSourceMode(props.pickerDataSource ?? block.dataSource?.type);
  if (!mode) return null;

  const valueField = getStringProp(props.valueField) || 'id';
  const displayField = getStringProp(props.displayField) || 'name';
  const searchKeyword = getStringProp(context?.pickerSearch).trim() || undefined;
  const searchField =
    getStringProp(props.searchField) || getStringProp(block.dataSource?.searchField) || displayField;
  const searchParameter =
    getStringProp(props.searchParameter) ||
    getStringProp(block.dataSource?.searchParameter) ||
    'keyword';
  const pageSize =
    getPositiveInteger(props.pageSize) ??
    getPositiveInteger(props.limit) ??
    getPositiveInteger(block.dataSource?.pageSize) ??
    getPositiveInteger(block.dataSource?.size) ??
    20;
  const parameters =
    getRecordPropOrUndefined(props.parameters) ??
    getRecordPropOrUndefined(props.pickerParameters) ??
    getRecordPropOrUndefined(block.dataSource?.parameters);

  if (mode === 'named-query') {
    const queryCode =
      getStringProp(props.pickerQueryCode) ||
      getStringProp(props.queryCode) ||
      getStringProp(props.namedQueryCode) ||
      getStringProp(props.pickerSource) ||
      getNamedQueryCode(block.dataSource ?? {});
    return queryCode
      ? {
          mode,
          source: queryCode,
          valueField,
          displayField,
          pageSize,
          parameters,
          searchKeyword,
          searchField,
          searchParameter,
        }
      : null;
  }

  const modelCode =
    getStringProp(props.pickerSource) ||
    getStringProp(props.modelCode) ||
    getStringProp(block.dataSource?.modelCode) ||
    getStringProp(block.dataSource?.model);
  return modelCode
    ? {
        mode,
        source: modelCode,
        valueField,
        displayField,
        pageSize,
        searchKeyword,
        searchField,
        searchParameter,
      }
    : null;
}

function normalizePickerDataSourceMode(value: unknown): PickerDataSourceRequest['mode'] | null {
  const mode = getStringProp(value).replace(/[_\s]/g, '-').toLowerCase();
  if (mode === 'model' || mode === 'query-builder' || mode === 'dynamic-model') return 'model';
  if (mode === 'namedquery' || mode === 'named-query' || mode === 'nq') return 'named-query';
  return null;
}

function getNamedQueryCode(dataSource: Record<string, unknown>): string {
  return (
    getStringProp(dataSource.queryCode) ||
    getStringProp(dataSource.namedQueryCode) ||
    getStringProp(dataSource.namedQuery) ||
    getStringProp(dataSource.code)
  );
}

function mapRowsToPickerOptions(
  rows: Record<string, unknown>[],
  request: PickerDataSourceRequest,
): RuntimePickerOption[] {
  return rows.flatMap((row) => {
    const optionValue =
      row[request.valueField] ?? row.id ?? row.pid ?? row.code ?? row.key ?? row.value;
    const optionLabel =
      row[request.displayField] ?? row.name ?? row.title ?? row.label ?? optionValue;
    const value = formatCell(optionValue);
    const label = formatCell(optionLabel);
    return value ? [{ label: label || value, value, record: row }] : [];
  });
}

function mapQueryRowsToWidgetData(
  block: DslBlockV3,
  rows: Record<string, unknown>[],
  query: WidgetDataShape,
  source: string,
): RuntimeWidgetData {
  if (!rows.length) {
    return {
      source,
      emptyText: getStringProp(block.props?.emptyText) || 'No data',
    };
  }

  if (block.widgetType === 'table') {
    const columns = resolveColumns(block, rows, query);
    return {
      source,
      columns,
      rows: rows.map((row) => columns.map((column) => formatCell(row[column]))),
    };
  }

  if (block.widgetType === 'bar-chart' || block.widgetType === 'line-chart') {
    return {
      source,
      series: rows.flatMap((row, index) => toSeriesPoint(block, row, query, index)),
    };
  }

  const firstRow = rows[0] ?? {};
  const value = selectMetricValue(block, firstRow, query);
  return {
    source,
    value: formatCell(value),
  };
}

function mapRowsToHelperBlockData(
  block: DslBlockV3,
  rows: Record<string, unknown>[],
  source: string,
): RuntimeHelperBlockData {
  if (block.blockType === 'ai-fill-banner') {
    return {
      source,
      suggestedFields: rows.flatMap((row, index) => {
        const field =
          formatCell(row.field) ||
          formatCell(row.fieldCode) ||
          formatCell(row.fieldcode) ||
          formatCell(row.code) ||
          `field_${index + 1}`;
        const label =
          formatCell(row.label) ||
          formatCell(row.fieldLabel) ||
          formatCell(row.fieldlabel) ||
          field;
        const value = formatCell(
          row.value ??
            row.suggestion ??
            row.suggestedValue ??
            row.suggestedvalue ??
            row.generatedValue ??
            row.generatedvalue,
        );
        return field || label || value ? [{ field, label, value }] : [];
      }),
      description: getFirstRowString(rows, 'description'),
      feedback: getFirstRowString(rows, 'feedback'),
      emptyText: rows.length ? undefined : getStringProp(block.props?.emptyText) || 'No suggestions',
    };
  }

  if (block.blockType === 'bpm-panel') {
    const firstRow = rows[0] ?? {};
    return {
      source,
      status: formatCell(firstRow.status ?? firstRow.state),
      description: formatCell(firstRow.description ?? firstRow.summary),
      assignee: formatCell(firstRow.assignee ?? firstRow.owner ?? firstRow.handler),
      dueAt: formatCell(firstRow.dueAt ?? firstRow.dueat ?? firstRow.due_at ?? firstRow.deadline),
      emptyText: rows.length
        ? undefined
        : getStringProp(block.props?.emptyText) || 'No workflow tasks',
      actions: resolveHelperActions(firstRow.actions).concat(
        rows.flatMap((row) => {
          const label = formatCell(row.actionLabel ?? row.actionlabel ?? row.label ?? row.action);
          const actionType = formatCell(row.actionType ?? row.actiontype ?? row.type ?? row.code);
          return label ? [{ label, actionType }] : [];
        }),
      ),
    };
  }

  if (block.blockType === 'activity-timeline') {
    return {
      source,
      items: rows.flatMap((row) => {
        const action = formatCell(row.action ?? row.title ?? row.event);
        if (!action) return [];
        return [
          {
            actor: formatCell(row.actor ?? row.user ?? row.operator),
            action,
            time: formatCell(row.time ?? row.createdAt ?? row.createdat ?? row.created_at),
            description: formatCell(row.description ?? row.detail ?? row.message),
          },
        ];
      }),
      emptyText: rows.length ? undefined : getStringProp(block.props?.emptyText) || 'No activity yet',
    };
  }

  if (block.blockType === 'field-history') {
    return {
      source,
      entries: rows.flatMap((row) => {
        const field = formatCell(row.field ?? row.fieldCode ?? row.fieldcode ?? row.label);
        if (!field) return [];
        return [
          {
            field,
            from: formatCell(row.from ?? row.oldValue ?? row.oldvalue ?? row.old_value),
            to: formatCell(row.to ?? row.newValue ?? row.newvalue ?? row.new_value),
            changedBy: formatCell(
              row.changedBy ?? row.changedby ?? row.changed_by ?? row.actor ?? row.user,
            ),
          },
        ];
      }),
      emptyText: rows.length ? undefined : getStringProp(block.props?.emptyText) || 'No field changes',
    };
  }

  return { source };
}

function getFirstRowString(rows: Record<string, unknown>[], key: string): string | undefined {
  const value = rows[0]?.[key];
  const text = formatCell(value);
  return text || undefined;
}

function resolveHelperActions(value: unknown): Array<{ label: string; actionType: string }> {
  const actions = normalizeRecordArray(value);
  return actions.flatMap((action) => {
    const label = formatCell(action.label ?? action.name ?? action.action);
    const actionType = formatCell(
      action.actionType ?? action.actiontype ?? action.type ?? action.code,
    );
    return label ? [{ label, actionType }] : [];
  });
}

function normalizeRecordArray(value: unknown): Record<string, unknown>[] {
  if (Array.isArray(value)) {
    return value.flatMap((item) => (isRecord(item) ? [item] : []));
  }
  if (typeof value === 'string') {
    try {
      const parsed: unknown = JSON.parse(value);
      return normalizeRecordArray(parsed);
    } catch {
      return [];
    }
  }
  return [];
}

function resolveColumns(
  block: DslBlockV3,
  rows: Record<string, unknown>[],
  query: WidgetDataShape,
): string[] {
  const propsColumns = getStringArray(block.props?.columns);
  if (propsColumns.length) return propsColumns;
  if (query.fields?.length) return query.fields;
  if (query.groupBy?.length || query.aggregations?.length) {
    return [
      ...(query.groupBy ?? []),
      ...(query.aggregations?.map((item) => item.alias || item.fieldCode) ?? []),
    ];
  }
  return Object.keys(rows[0] ?? {});
}

function toSeriesPoint(
  block: DslBlockV3,
  row: Record<string, unknown>,
  query: WidgetDataShape,
  index: number,
): Array<{ label: string; value: number }> {
  const labelKey =
    getStringProp(block.dataSource?.labelField) ||
    query.groupBy?.[0] ||
    query.fields?.find((field) => typeof row[field] === 'string') ||
    Object.keys(row).find((key) => typeof row[key] === 'string');
  const value = Number(selectMetricValue(block, row, query));
  if (!Number.isFinite(value)) return [];

  return [
    {
      label: labelKey ? formatCell(row[labelKey]) : `#${index + 1}`,
      value,
    },
  ];
}

function selectMetricValue(
  block: DslBlockV3,
  row: Record<string, unknown>,
  query: WidgetDataShape,
): unknown {
  const metricKey =
    getStringProp(block.dataSource?.metric) ||
    query.aggregations?.[0]?.alias ||
    query.aggregations?.[0]?.fieldCode ||
    query.fields?.find((field) => typeof row[field] === 'number') ||
    Object.keys(row).find((key) => typeof row[key] === 'number') ||
    query.fields?.[0] ||
    Object.keys(row)[0];
  return metricKey ? row[metricKey] : undefined;
}

function isSuccessCode(code: unknown): boolean {
  return code === '0' || code === 0;
}

function getOperationType(value: unknown): 'create' | 'update' | 'delete' | undefined {
  if (value === 'create' || value === 'update' || value === 'delete') return value;
  return undefined;
}

function getFiniteNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function getPositiveInteger(value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  const integerValue = Math.floor(value);
  return integerValue > 0 ? integerValue : undefined;
}

function getStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => (typeof item === 'string' ? [item] : []));
}

function uniqueStrings(values: string[]): string[] {
  return Array.from(new Set(values.filter(Boolean)));
}

function getRecordProp(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

function sanitizeRuntimeActionPayload(
  payload: Record<string, unknown>,
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): Record<string, unknown> {
  const sanitized: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(payload)) {
    if (key !== RESERVED_AUDIT_CONTEXT_KEY) {
      sanitized[key] = resolveRuntimePayloadValue(value, block, context);
    }
  }
  return sanitized;
}

function resolveRuntimePayloadValue(
  value: unknown,
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => resolveRuntimePayloadValue(item, block, context));
  }
  if (isRecord(value)) {
    return sanitizeRuntimeActionPayload(value, block, context);
  }
  if (typeof value !== 'string') {
    return value;
  }

  const exactMatch = value.match(EXACT_TEMPLATE_PATTERN);
  if (exactMatch) {
    return resolveRuntimeTemplateValue(exactMatch[1], block, context) ?? value;
  }

  return value.replace(TEMPLATE_TOKEN_PATTERN, (match, token: string) => {
    const resolved = resolveRuntimeTemplateValue(token, block, context);
    if (resolved === undefined) return match;
    if (typeof resolved === 'string') return resolved;
    if (typeof resolved === 'number' || typeof resolved === 'boolean') return String(resolved);
    return JSON.stringify(resolved);
  });
}

function resolveRuntimeTemplateString(
  value: string,
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): string {
  return formatCell(resolveRuntimePayloadValue(value, block, context));
}

function resolveRuntimeTemplateValue(
  token: string,
  block: DslBlockV3,
  context?: RuntimeExecutionContext,
): unknown {
  if (token === 'current.row') {
    return context?.currentRow;
  }
  if (token === 'current.rowId') {
    return context?.currentRowId;
  }

  const currentRowKey = token.startsWith('current.row.')
    ? token.slice('current.row.'.length)
    : '';
  if (currentRowKey) {
    return context?.currentRow?.[currentRowKey];
  }

  if (token === 'selected.rows') {
    return context?.selectedRows;
  }
  if (token === 'selected.rowIds') {
    return context?.selectedRowIds;
  }
  if (token === 'selected.count') {
    return context?.selectedRows?.length;
  }

  const formValueKey = token.startsWith('form.values.')
    ? token.slice('form.values.'.length)
    : '';
  if (formValueKey) {
    return context?.formValues?.[formValueKey];
  }

  const routeQueryKey = token.startsWith('route.query.')
    ? token.slice('route.query.'.length)
    : '';
  if (routeQueryKey) {
    return context?.routeQuery?.[routeQueryKey];
  }

  const auditContext = createRuntimeAuditContext(block, context);
  const values: Record<string, unknown> = {
    'page.id': auditContext.pageId,
    'page.kind': auditContext.pageKind,
    'schema.version': auditContext.schemaVersion,
    'block.id': auditContext.blockId,
    'block.type': auditContext.blockType,
    'block.path': auditContext.blockPath,
    'action.type': auditContext.actionType,
    'widget.type': auditContext.widgetType,
    source: auditContext.source,
  };
  return values[token];
}

function getRecordPropOrUndefined(value: unknown): Record<string, unknown> | undefined {
  return isRecord(value) ? value : undefined;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function getStringProp(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function formatCell(value: unknown): string {
  if (value == null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}
