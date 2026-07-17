import { useEffect, useMemo, useRef, useState } from 'react';
import { BaseFormulaEditor } from '~/ui/base-fields';
import type { FieldAdapter } from '~/ui/field-adapter';
import type { FormulaField } from '~/framework/smart/components/formula/FormulaEditor';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionAction,
  type DecisionActionCatalog,
  type DecisionFactCatalog,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { factCatalogToFieldOptions } from '~/shared/decision/ui/factCatalogAdapter';
import {
  actionDefinitionFor,
  actionFieldInputKind,
  actionSchemaFields,
  type ActionSchemaField,
  payloadToJson as actionPayloadToJson,
  readActionFieldValue,
  writeActionFieldValue,
} from '~/shared/decision/ui/actionSchemaFields';
import { decisionStatusLabel } from '~/shared/decision/ui/statusLabels';
import {
  normalizeDecisionOutputFields,
  type DecisionOutputSchemaSource,
} from '~/shared/decision/ui/decisionOutputSchema';
import { resolveDecisionActionAvailability } from '~/shared/decision/ui/actionAvailability';

type ActionPlanInput =
  | DecisionActionPlanDraft
  | string
  | {
      type?: string;
      value?: string;
      null?: boolean;
    };

interface ActionPlanFieldOption {
  scope?: string;
  path?: string;
  label?: string;
  dataType?: string;
  modelName?: string;
  code?: string;
  name?: string;
  group?: string;
  insertion?: string;
}

type ActionPlanDecisionOutput =
  | DecisionOutputSchemaSource;

type FailureStrategy =
  | 'FAIL_FAST'
  | 'CONTINUE_ON_ERROR'
  | 'ALL_OR_NOTHING'
  | 'RETRY_ASYNC'
  | 'DEAD_LETTER';

interface DecisionActionPlanBlockProps {
  block?: {
    props?: {
      valueField?: string;
      value?: ActionPlanInput;
      initialValue?: ActionPlanInput;
      title?: string;
      triggerLabel?: string;
      defaultTrigger?: string;
      logsUrl?: string;
      readOnly?: boolean;
      consumerType?: string;
      record?: Record<string, unknown>;
      fields?: ActionPlanFieldOption[];
      fieldCatalogModelCode?: string;
      fieldCatalogModelCodeField?: string;
      decisionOutputs?: ActionPlanDecisionOutput[];
      decisionOutputSchema?: unknown;
    };
  };
  runtime?: {
    getFieldValue?: (fieldCode: string) => unknown;
    updateField?: (fieldCode: string, value: unknown) => void;
  };
  api?: {
    getActionCatalog: () => Promise<DecisionActionCatalog>;
    getFactCatalog?: (modelCode?: string) => Promise<DecisionFactCatalog>;
  };
}

export interface DecisionActionDraft {
  type: string;
  target?: string;
  order?: number;
  payload?: Record<string, unknown>;
  idempotencyKeyTemplate?: string;
}

export interface DecisionActionPlanDraft {
  trigger?: string;
  failureStrategy?: FailureStrategy;
  actions: DecisionActionDraft[];
  executionEffect?: {
    lastStatus?: string;
    traceId?: string;
    lastRunAt?: string;
    summary?: string;
  };
}

const BUILTIN_ACTIONS: DecisionAction[] = [
  { actionType: 'NOTIFY', label: '站内通知', category: 'messaging' },
  { actionType: 'SEND_SMS', label: '发送短信', category: 'messaging' },
  { actionType: 'SEND_IM', label: '发送 IM', category: 'messaging' },
  { actionType: 'WEBHOOK', label: 'Webhook', category: 'integration' },
  { actionType: 'START_PROCESS', label: '启动流程', category: 'workflow' },
  { actionType: 'CREATE_TASK', label: '创建任务', category: 'collaboration' },
  { actionType: 'CC_TASK', label: '抄送任务', category: 'collaboration' },
  { actionType: 'ADD_COMMENT', label: '添加评论', category: 'collaboration' },
  { actionType: 'UPDATE_RECORD', label: '更新记录', category: 'data' },
  { actionType: 'WRITE_AUDIT', label: '写入审计', category: 'audit' },
];

const FAILURE_STRATEGIES: FailureStrategy[] = [
  'CONTINUE_ON_ERROR',
  'FAIL_FAST',
  'ALL_OR_NOTHING',
  'RETRY_ASYNC',
  'DEAD_LETTER',
];

const FAILURE_STRATEGY_LABELS: Record<FailureStrategy, string> = {
  CONTINUE_ON_ERROR: '失败后继续',
  FAIL_FAST: '失败即停止',
  ALL_OR_NOTHING: '全部成功才提交',
  RETRY_ASYNC: '异步重试',
  DEAD_LETTER: '进入死信',
};

const FIELD_SCOPE_LABELS: Record<string, string> = {
  record: '当前记录',
  sla: 'SLA 上下文',
  process: '流程上下文',
  task: '任务上下文',
  actor: '参与人',
  tenant: '租户',
  event: '事件上下文',
  decision: '规则执行',
};

function defaultApi() {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

function parseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
}

function recordOf(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function failureStrategyValue(value: unknown): FailureStrategy | undefined {
  const text = stringValue(value)?.toUpperCase();
  if (!text) return undefined;
  return FAILURE_STRATEGIES.includes(text as FailureStrategy) ? (text as FailureStrategy) : undefined;
}

function normalizeAction(value: unknown): DecisionActionDraft | null {
  const record = recordOf(value);
  if (!record) return null;
  const type = stringValue(record.type);
  if (!type) return null;
  return {
    type,
    target: stringValue(record.target),
    order: numberValue(record.order),
    payload: recordOf(record.payload) ?? {},
    idempotencyKeyTemplate: stringValue(record.idempotencyKeyTemplate),
  };
}

function parseActionPlan(value: unknown, defaultTrigger?: string): DecisionActionPlanDraft {
  const envelope = recordOf(value);
  const raw =
    typeof value === 'string'
      ? parseJson(value)
      : typeof envelope?.value === 'string'
        ? parseJson(envelope.value)
        : value;
  const record = recordOf(raw);
  const actions = Array.isArray(record?.actions)
    ? record.actions
        .map((action) => normalizeAction(action))
        .filter((action): action is DecisionActionDraft => Boolean(action))
    : [];
  const effect = recordOf(record?.executionEffect);
  return {
    trigger: typeof record?.trigger === 'string' ? record.trigger : defaultTrigger,
    failureStrategy: failureStrategyValue(record?.failureStrategy ?? record?.failure_strategy),
    actions,
    executionEffect: effect
      ? {
          lastStatus: stringValue(effect.lastStatus),
          traceId: stringValue(effect.traceId),
          lastRunAt: stringValue(effect.lastRunAt),
          summary: stringValue(effect.summary),
        }
      : undefined,
  };
}

function actionLabel(actionType: string, catalog: DecisionAction[]) {
  return (
    catalog.find((item) => item.actionType === actionType)?.label ||
    BUILTIN_ACTIONS.find((item) => item.actionType === actionType)?.label ||
    actionType
  );
}

function actionCatalogItem(actionType: string, catalog: DecisionAction[]) {
  return catalog.find((item) => item.actionType === actionType) ||
    BUILTIN_ACTIONS.find((item) => item.actionType === actionType);
}

function actionAvailability(action?: DecisionAction, consumerType?: string) {
  return resolveDecisionActionAvailability(action, consumerType);
}

function actionOptionLabel(action: DecisionAction, catalog: DecisionAction[], consumerType?: string) {
  const label = actionLabel(action.actionType, catalog);
  return actionAvailability(action, consumerType).unavailable ? `${label}（不可用）` : label;
}

function payloadTitle(payload: Record<string, unknown> | undefined): string {
  const title = stringValue(payload?.title);
  if (title) return title;
  const content = stringValue(payload?.content) ?? stringValue(payload?.message);
  return content ?? '未配置负载';
}

function payloadToJson(payload: Record<string, unknown> | undefined): string {
  return actionPayloadToJson(payload);
}

function payloadSummary(payload: Record<string, unknown> | undefined): string {
  const content =
    stringValue(payload?.content) ??
    stringValue(payload?.message) ??
    stringValue(payload?.taskTitle) ??
    stringValue(payload?.template);
  return content ?? payloadTitle(payload);
}

function parsePayloadJson(value: string): Record<string, unknown> {
  return recordOf(parseJson(value)) ?? {};
}

function catalogWithFallback(catalog: DecisionAction[]) {
  const seen = new Set<string>();
  return [...catalog, ...BUILTIN_ACTIONS].filter((action) => {
    if (!action.actionType || seen.has(action.actionType)) return false;
    seen.add(action.actionType);
    return true;
  });
}

function normalizeFormulaField(field: ActionPlanFieldOption): FormulaField | null {
  const scope = field.scope?.trim();
  const path = field.path?.trim();
  const code = field.code?.trim() || (scope && path ? `${scope}.${path}` : undefined);
  if (!code) return null;
  const name = field.name?.trim() || field.label?.trim() || code;
  const group = field.group?.trim() || field.modelName?.trim() || FIELD_SCOPE_LABELS[scope ?? ''] || '字段';
  return {
    code,
    name,
    group,
    insertion: field.insertion ?? `\${${code}}`,
  };
}

function normalizeDecisionOutputField(output: { id: string; label: string }): FormulaField | null {
  const code = output.id.trim();
  if (!code) return null;
  const label = output.label.trim() || code;
  return {
    code: `decision.outputs.${code}`,
    name: label,
    group: '规则输出',
    insertion: `\${decision.outputs.${code}}`,
  };
}

function uniqueFormulaFields(fields: FormulaField[]): FormulaField[] {
  const seen = new Set<string>();
  return fields.filter((field) => {
    if (!field.code || seen.has(field.code)) return false;
    seen.add(field.code);
    return true;
  });
}

function actionFormulaFields(
  fields: ActionPlanFieldOption[] | undefined,
  decisionOutputs: ActionPlanDecisionOutput[] | undefined,
  decisionOutputSchema: unknown,
): FormulaField[] {
  const outputs = normalizeDecisionOutputFields(decisionOutputs, decisionOutputSchema);
  if ((!fields || fields.length === 0) && outputs.length === 0) {
    return [];
  }
  return uniqueFormulaFields([
    ...(fields ?? []).map(normalizeFormulaField).filter((field): field is FormulaField => Boolean(field)),
    ...outputs
      .map(normalizeDecisionOutputField)
      .filter((field): field is FormulaField => Boolean(field)),
    {
      code: 'decision.matched',
      name: '规则是否命中',
      group: '规则执行',
      insertion: '${decision.matched}',
    },
    {
      code: 'decision.status',
      name: '规则状态',
      group: '规则执行',
      insertion: '${decision.status}',
    },
  ]);
}

function resolveFieldCatalogModelCode(
  props: NonNullable<DecisionActionPlanBlockProps['block']>['props'],
  runtime?: DecisionActionPlanBlockProps['runtime'],
): string | undefined {
  const direct = stringValue(props?.fieldCatalogModelCode);
  if (direct) return direct;
  const field = stringValue(props?.fieldCatalogModelCodeField);
  if (!field) return undefined;
  const runtimeValue = templatePrimitive(runtime?.getFieldValue?.(field));
  if (runtimeValue) return runtimeValue;
  return templatePrimitive(readRecordField(props?.record, field));
}

function editableFields(action: DecisionActionDraft, catalog: DecisionAction[]): ActionSchemaField[] {
  const fields = actionSchemaFields(actionDefinitionFor(action.type, catalog));
  if (fields.length > 0) return fields;
  return [{ path: 'target', label: '目标', dataType: 'string', required: false }];
}

function readRecordField(record: Record<string, unknown> | undefined, fieldCode: string): unknown {
  if (!record || !fieldCode) return undefined;
  if (Object.prototype.hasOwnProperty.call(record, fieldCode)) return record[fieldCode];
  const camelField = fieldCode.replace(/_([a-z])/g, (_match, letter: string) =>
    letter.toUpperCase(),
  );
  if (Object.prototype.hasOwnProperty.call(record, camelField)) return record[camelField];
  const snakeField = fieldCode.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  if (Object.prototype.hasOwnProperty.call(record, snakeField)) return record[snakeField];
  return undefined;
}

function templatePrimitive(value: unknown): string | undefined {
  if (value == null) return undefined;
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  const record = recordOf(value);
  const envelopeValue = record?.value;
  if (
    typeof envelopeValue === 'string' ||
    typeof envelopeValue === 'number' ||
    typeof envelopeValue === 'boolean'
  ) {
    return String(envelopeValue);
  }
  return undefined;
}

function resolveTemplate(
  template: string | undefined,
  runtime?: DecisionActionPlanBlockProps['runtime'],
  record?: Record<string, unknown>,
): string | undefined {
  if (!template) return undefined;
  return template.replace(/\{([a-zA-Z0-9_.-]+)\}/g, (token, fieldCode: string) => {
    const value = templatePrimitive(runtime?.getFieldValue?.(fieldCode));
    const recordValue = value ?? templatePrimitive(readRecordField(record, fieldCode));
    if (recordValue == null) return token;
    return encodeURIComponent(recordValue);
  });
}

function ActionFormulaField({
  ariaLabel,
  field,
  formulaFields,
  onChange,
  readOnly,
  testId,
  value,
}: {
  ariaLabel: string;
  field: ActionSchemaField;
  formulaFields: FormulaField[];
  onChange: (value: string) => void;
  readOnly: boolean;
  testId: string;
  value: string;
}) {
  const adapter = useMemo<FieldAdapter<string>>(
    () => ({
      value,
      setValue: onChange,
      disabled: readOnly,
      readOnly,
      required: field.required,
    }),
    [field.required, onChange, readOnly, value],
  );

  return (
    <div className="decision-action-schema-field" data-testid={testId}>
      <span>
        {field.label}
        {field.required && <em>必填</em>}
      </span>
      <BaseFormulaEditor
        adapter={adapter}
        className="decision-action-formula-editor"
        fields={formulaFields}
        name={ariaLabel}
        placeholder={`输入${field.label}`}
        showHelp={false}
      />
    </div>
  );
}

export function DecisionActionPlanBlock({ block, runtime, api }: DecisionActionPlanBlockProps) {
  const props = block?.props ?? {};
  const rawValue =
    props.value ??
    (props.valueField ? runtime?.getFieldValue?.(props.valueField) : undefined) ??
    props.initialValue;
  const [plan, setPlan] = useState<DecisionActionPlanDraft>(() =>
    parseActionPlan(rawValue, props.defaultTrigger),
  );
  const [catalog, setCatalog] = useState<DecisionAction[]>(BUILTIN_ACTIONS);
  const [factCatalogFields, setFactCatalogFields] = useState<ActionPlanFieldOption[]>([]);
  const [catalogError, setCatalogError] = useState('');
  const defaultApiRef = useRef<ReturnType<typeof defaultApi> | null>(null);
  const readOnly = props.readOnly === true;
  const consumerType = props.consumerType?.trim().toUpperCase();
  const actionOptions = useMemo(() => catalogWithFallback(catalog), [catalog]);
  const fieldCatalogModelCode = resolveFieldCatalogModelCode(props, runtime);
  const formulaFields = useMemo(
    () =>
      actionFormulaFields(
        [...factCatalogFields, ...(props.fields ?? [])],
        props.decisionOutputs,
        props.decisionOutputSchema,
      ),
    [factCatalogFields, props.fields, props.decisionOutputs, props.decisionOutputSchema],
  );

  useEffect(() => {
    let cancelled = false;
    const client = api ?? (defaultApiRef.current ??= defaultApi());
    client
      .getActionCatalog()
      .then((nextCatalog) => {
        if (!cancelled) {
          setCatalog(catalogWithFallback(nextCatalog.actions ?? []));
          setCatalogError('');
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setCatalog(BUILTIN_ACTIONS);
          setCatalogError(error instanceof Error ? error.message : String(error));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    let cancelled = false;
    if (!fieldCatalogModelCode) {
      setFactCatalogFields((current) => (current.length === 0 ? current : []));
      return () => {
        cancelled = true;
      };
    }
    const client = api ?? (defaultApiRef.current ??= defaultApi());
    const getFactCatalog = client.getFactCatalog;
    if (!getFactCatalog) {
      setFactCatalogFields((current) => (current.length === 0 ? current : []));
      return () => {
        cancelled = true;
      };
    }
    getFactCatalog(fieldCatalogModelCode)
      .then((catalog) => {
        if (cancelled) return;
        setFactCatalogFields(factCatalogToFieldOptions(catalog));
      })
      .catch(() => {
        if (!cancelled) {
          setFactCatalogFields((current) => (current.length === 0 ? current : []));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [api, fieldCatalogModelCode]);

  const emitPlan = (nextPlan: DecisionActionPlanDraft) => {
    setPlan(nextPlan);
    if (props.valueField) {
      runtime?.updateField?.(props.valueField, nextPlan);
    }
  };

  const addAction = () => {
    const order = Math.max(0, ...plan.actions.map((action) => action.order ?? 0)) + 10;
    emitPlan({
      ...plan,
      trigger: plan.trigger ?? props.defaultTrigger,
      actions: [
        ...plan.actions,
        {
          type: actionOptions[0]?.actionType ?? 'NOTIFY',
          target: '',
          order,
          payload: {},
        },
      ],
    });
  };

  const updateAction = (index: number, patch: Partial<DecisionActionDraft>) => {
    const actions = plan.actions.slice();
    actions[index] = { ...actions[index], ...patch };
    emitPlan({ ...plan, actions });
  };

  const updateFailureStrategy = (failureStrategy: FailureStrategy) => {
    emitPlan({ ...plan, failureStrategy });
  };

  const updateActionField = (index: number, field: ActionSchemaField, value: string) => {
    const action = plan.actions[index];
    if (!action) return;
    try {
      const next = writeActionFieldValue(action.target, action.payload, field, value);
      updateAction(index, { target: next.target, payload: next.payload });
    } catch {
      // Keep the previous structured payload until the JSON field becomes valid again.
    }
  };

  const removeAction = (index: number) => {
    emitPlan({ ...plan, actions: plan.actions.filter((_, idx) => idx !== index) });
  };

  const title = props.title ?? '动作计划';
  const triggerLabel = props.triggerLabel ?? plan.trigger ?? '规则命中';
  const failureStrategy = plan.failureStrategy ?? 'CONTINUE_ON_ERROR';
  const effect = plan.executionEffect;
  const logsUrl = resolveTemplate(props.logsUrl, runtime, props.record);

  return (
    <section className="decision-action-plan-block" data-testid="decision-action-plan-block">
      <div className="decision-action-plan-head">
        <div>
          <span className="decision-action-kicker">动作目录</span>
          <h3>{title}</h3>
          <p>{triggerLabel}</p>
        </div>
        <div className="decision-action-plan-tools">
          {logsUrl && (
            <a className="decisionops-inline-link" href={logsUrl}>
              查看日志
            </a>
          )}
          {!readOnly && (
            <button type="button" data-testid="dap-add-action" onClick={addAction}>
              添加动作
            </button>
          )}
        </div>
      </div>

      <div className="decision-action-strategy" data-testid="dap-failure-strategy">
        <span>失败策略</span>
        {readOnly ? (
          <strong>{FAILURE_STRATEGY_LABELS[failureStrategy]}</strong>
        ) : (
          <select
            aria-label="action-failure-strategy"
            value={failureStrategy}
            onChange={(event) => updateFailureStrategy(event.target.value as FailureStrategy)}
          >
            {FAILURE_STRATEGIES.map((strategy) => (
              <option key={strategy} value={strategy}>
                {FAILURE_STRATEGY_LABELS[strategy]}
              </option>
            ))}
          </select>
        )}
      </div>

      {effect && (
        <div className="decision-action-effect" data-testid="dap-execution-effect">
          <strong>最近运行 {decisionStatusLabel(effect.lastStatus)}</strong>
          <span>{effect.summary ?? effect.lastRunAt ?? '等待下一次触发'}</span>
          {effect.traceId && <code>{effect.traceId}</code>}
        </div>
      )}

      {catalogError && (
        <div className="decision-rule-error" data-testid="dap-catalog-error">
          动作目录暂不可用，已使用内置动作类型
        </div>
      )}

      {plan.actions.length === 0 ? (
        <div className="decision-rule-empty" data-testid="dap-empty">
          还没有动作。触发后不会发送通知、短信、IM、任务或审计。
        </div>
      ) : (
        <div className="decision-action-grid">
          {plan.actions.map((action, index) => {
            const fields = editableFields(action, actionOptions);
            const availability = actionAvailability(
              actionCatalogItem(action.type, actionOptions),
              consumerType,
            );
            return (
            <div className="decision-action-card" data-testid={`dap-action-${index}`} key={index}>
              <div className="decision-action-card-title">
                <strong>{actionLabel(action.type, actionOptions)}</strong>
                {availability.unavailable && (
                  <span className="decision-action-availability-badge">不可用</span>
                )}
                <span>#{action.order ?? index + 1}</span>
              </div>
              {availability.unavailable && (
                <div className="decision-action-availability" data-testid={`dap-action-availability-${index}`}>
                  <div>{availability.reason}</div>
                  {availability.providerSummary && (
                    <div className="mt-1" data-testid={`dap-action-provider-${index}`}>
                      {availability.providerSummary}
                    </div>
                  )}
                </div>
              )}
              {readOnly ? (
                <dl className="decision-action-readonly">
                  <div>
                    <dt>目标</dt>
                    <dd>{action.target || '-'}</dd>
                  </div>
                  <div>
                    <dt>负载</dt>
                    <dd>{payloadSummary(action.payload)}</dd>
                  </div>
                  {action.idempotencyKeyTemplate && (
                    <div>
                      <dt>幂等键</dt>
                      <dd>{action.idempotencyKeyTemplate}</dd>
                    </div>
                  )}
                </dl>
              ) : (
                <>
                  <div className="decision-action-fields">
                    <label>
                      动作类型
                      <select
                        aria-label={`action-type-${index}`}
                        disabled={readOnly}
                        value={action.type}
                        onChange={(event) => updateAction(index, { type: event.target.value })}
                      >
                        {actionOptions.map((option) => (
                          <option key={option.actionType} value={option.actionType}>
                            {actionOptionLabel(option, actionOptions, consumerType)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label>
                      顺序
                      <input
                        aria-label={`action-order-${index}`}
                        disabled={readOnly}
                        type="number"
                        value={action.order ?? index + 1}
                        onChange={(event) =>
                          updateAction(index, { order: Number(event.target.value) })
                        }
                      />
                    </label>
                  </div>
                  <div className="decision-action-schema-fields" data-testid={`dap-schema-fields-${index}`}>
                    {fields.map((field) => {
                      const inputKind = actionFieldInputKind(field);
                      const ariaLabel =
                        field.path === 'target'
                          ? `action-target-${index}`
                          : `action-field-${index}-${field.path}`;
                      const value = readActionFieldValue(action.target, action.payload, field);
                      const testId = `dap-action-field-${index}-${field.path}`;
                      const canUseFormula =
                        formulaFields.length > 0 &&
                        field.path !== 'target' &&
                        (inputKind === 'text' || inputKind === 'textarea');
                      if (canUseFormula) {
                        return (
                          <ActionFormulaField
                            key={field.path}
                            ariaLabel={ariaLabel}
                            field={field}
                            formulaFields={formulaFields}
                            readOnly={readOnly}
                            testId={testId}
                            value={value}
                            onChange={(nextValue) => updateActionField(index, field, nextValue)}
                          />
                        );
                      }
                      return (
                        <label key={field.path} className="decision-action-schema-field" data-testid={testId}>
                          <span>
                            {field.label}
                            {field.required && <em>必填</em>}
                          </span>
                          {inputKind === 'textarea' || inputKind === 'json' ? (
                            <textarea
                              aria-label={ariaLabel}
                              disabled={readOnly}
                              value={value}
                              onChange={(event) =>
                                updateActionField(index, field, event.target.value)
                              }
                            />
                          ) : (
                            <input
                              aria-label={ariaLabel}
                              disabled={readOnly}
                              value={value}
                              onChange={(event) =>
                                updateActionField(index, field, event.target.value)
                              }
                            />
                          )}
                        </label>
                      );
                    })}
                  </div>
                  <div className="decision-action-payload-summary">
                    <span>负载摘要</span>
                    <strong>{payloadTitle(action.payload)}</strong>
                  </div>
                  <details className="decision-action-payload-advanced">
                    <summary>高级负载</summary>
                    <textarea
                      aria-label={`action-payload-${index}`}
                      disabled={readOnly}
                      value={payloadToJson(action.payload)}
                      onChange={(event) =>
                        updateAction(index, { payload: parsePayloadJson(event.target.value) })
                      }
                    />
                  </details>
                </>
              )}
              {!readOnly && (
                <button
                  type="button"
                  className="decision-action-remove"
                  aria-label={`remove-action-${index}`}
                  onClick={() => removeAction(index)}
                >
                  删除
                </button>
              )}
            </div>
            );
          })}
        </div>
      )}
    </section>
  );
}

export default DecisionActionPlanBlock;
