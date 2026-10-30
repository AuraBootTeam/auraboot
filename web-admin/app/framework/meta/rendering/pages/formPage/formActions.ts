import { hasButtonPermissions } from '~/framework/meta/utils/buttonPermissions';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { resolvePageTargetPath } from '~/framework/meta/runtime/actions/resolvePageTarget';

export function resolveFormButtonContent(
  button: Record<string, any>,
  locale: string,
  t: (key: string) => string,
): string {
  return (
    getLocalizedText(button.content || button.label, locale, t) ||
    (typeof button.action === 'string' ? t(`action.${button.action}`) : undefined) ||
    button.code
  );
}

export function resolveAsyncCommandDispatch(
  responseData: any,
  submitMode?: unknown,
): { taskCode: string } | null {
  const dispatch =
    responseData?.data && typeof responseData.data === 'object' ? responseData.data : responseData;
  // A configured task-detail page owns progress polling after durable admission.
  // A generic async acknowledgement without a task record must still be awaited.
  if (
    submitMode === 'redirect' &&
    dispatch?.queued === true &&
    typeof dispatch?.taskId === 'string' &&
    dispatch.taskId.trim()
  )
    return null;
  const taskCode = dispatch?.taskCode;
  if (dispatch?.async === true && typeof taskCode === 'string' && taskCode.trim()) {
    return { taskCode: taskCode.trim() };
  }
  return null;
}

export async function pollFormAsyncCommandTask(taskCode: string, token?: string): Promise<any> {
  const terminal = new Set(['completed', 'failed', 'cancelled']);
  for (let attempt = 0; attempt < 600; attempt++) {
    const result = await fetchResult(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
      method: 'get',
      token,
    });
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(
        (result as any).desc || (result as any).message || 'Async task status unavailable',
      );
    }
    const task = (result as any).data || {};
    const status = String(task.status || '').toLowerCase();
    if (terminal.has(status)) {
      if (status === 'completed') return task.resultData ?? {};
      if (status === 'cancelled') throw new Error('Task cancelled');
      throw new Error(task.errorMessage || 'Async task failed');
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  throw new Error('Async task timed out');
}

/**
 * Extract the action type string from a button action that can be either
 * a plain string (e.g. "save") or an object (e.g. {type: "command", command: "sc:update_showcase"}).
 */
export function resolveActionType(action: unknown): string {
  if (typeof action === 'string') return action;
  if (action && typeof action === 'object' && 'type' in action) {
    return String((action as Record<string, unknown>).type || '');
  }
  return '';
}

export function shouldBypassFormSubmit(
  button: { code?: string; commandCode?: string; action?: unknown } | null | undefined,
  actionType: string,
): boolean {
  const normalizedActionType = String(actionType || '').toLowerCase();
  if (['navigate', 'cancel', 'back', 'close', 'refresh', 'reload'].includes(normalizedActionType)) {
    return true;
  }

  const action = button?.action;
  const actionCommand =
    action && typeof action === 'object' ? (action as Record<string, unknown>).command : undefined;
  const buttonCode = String(button?.code || '').toLowerCase();
  return (
    ['cancel', 'back', 'close', 'refresh', 'reload'].includes(buttonCode) &&
    !button?.commandCode &&
    typeof actionCommand !== 'string'
  );
}

export function isFormButtonDisabled(
  button: { code?: string; primary?: boolean },
  loading: boolean,
  submitting: boolean,
  submitReady: boolean,
): boolean {
  if (loading || submitting) return true;

  const code = String(button.code || '').toLowerCase();
  const submitsForm = button.primary === true || code === 'submit' || code === 'save';
  return submitsForm && !submitReady;
}

export function canRenderFormButton(
  button: { permissionCode?: string; permissionCodes?: string[] },
  hasPermission: (permissionCode: string) => boolean,
): boolean {
  return hasButtonPermissions(button, hasPermission);
}

/** Deny authoring only when every active primary action declares a denied permission. */
export function isFormAuthoringDenied(
  activeButtons: Array<{ primary?: boolean; permissionCode?: string; permissionCodes?: string[] }>,
  hasPermission: (permissionCode: string) => boolean,
): boolean {
  const primaryActions = activeButtons.filter((button) => button.primary === true);
  return (
    primaryActions.length > 0 &&
    primaryActions.every((button) => !hasButtonPermissions(button, hasPermission))
  );
}

/** Match dynamic CRUD's server permission at the form boundary, before editable children mount. */
export function isPureCrudFormAuthoringDenied(
  schema: {
    modelCode?: string;
    commands?: Record<string, unknown>;
    extension?: Record<string, any>;
  },
  buttons: Array<{ action?: unknown; commandCode?: string }>,
  editing: boolean,
  externalSubmit: boolean,
  hasPermission: (code: string) => boolean,
): boolean {
  if (
    externalSubmit ||
    !schema.modelCode ||
    Object.values(schema.commands || {}).some(Boolean) ||
    schema.extension?.submitEndpoint
  )
    return false;
  const writes = buttons.filter((button) =>
    ['save', 'submit', 'create', 'update'].includes(resolveActionType(button.action).toLowerCase()),
  );
  if (
    !writes.length ||
    writes.some((button) => button.commandCode || typeof button.action === 'object')
  )
    return false;
  return !hasPermission(`model.${schema.modelCode}.${editing ? 'update' : 'create'}`);
}

/**
 * Resolve the redirect target after a successful form submit.
 *
 * Honors `schema.extension.afterSubmitRedirect` when set. Supports placeholder
 * substitution from the response data (e.g. `{pid}` is replaced with the new
 * record's pid). Falls back to the list page (`/p/{tableName}`) when no
 * override is configured.
 */
export function resolveAfterSubmitRedirect(
  schema: any,
  tableName: string,
  responseData: any,
  recordPid: string | null | undefined,
): string {
  const template = (schema?.extension as any)?.afterSubmitRedirect;
  if (typeof template !== 'string' || !template) {
    return `/p/${tableName}`;
  }
  // Build placeholder bag: response payload fields + the original recordPid.
  const bag: Record<string, any> = {
    ...(responseData && typeof responseData === 'object' ? responseData : {}),
  };
  const nestedData =
    responseData && typeof responseData === 'object' && (responseData as any).data
      ? (responseData as any).data
      : null;
  if (nestedData && typeof nestedData === 'object') {
    Object.assign(bag, nestedData);
  }
  if (bag.pid == null && bag.recordPid != null) bag.pid = bag.recordPid;
  if (bag.id == null && bag.recordPid != null) bag.id = bag.recordPid;
  if (recordPid && bag.pid == null) bag.pid = recordPid;
  if (recordPid && bag.id == null) bag.id = recordPid;
  return template.replace(/\{(\w+)\}/g, (_match, key: string) => {
    const value = bag[key];
    return value != null ? String(value) : '';
  });
}

/**
 * Resolve the target of the header back link.
 *
 * Honors `schema.extension.backTo`, which takes the same shape as a `navigate`
 * action target (pageKey, cross-designer reference, or absolute path), plus the
 * literal `"none"` for a page with no parent to return to. Falls back to
 * `/p/{tableName}` — correct for a CRUD form reached from its own list page,
 * wrong for a command-entry form whose URL prefix is a pageKey rather than a
 * model (there is no `{prefix}_list` page to land on).
 *
 * @returns the back-link path, or null when the header should render no link
 */
export function resolveFormBackLink(schema: any, tableName: string): string | null {
  const target = (schema?.extension as any)?.backTo;
  if (typeof target === 'string' && target.trim()) {
    const trimmed = target.trim();
    if (trimmed === 'none') return null;
    const resolved = resolvePageTargetPath(trimmed);
    if (resolved) return resolved;
  }
  return `/p/${tableName}`;
}

/**
 * Resolve the edit-mode record-prefill fetch endpoint.
 *
 * When the schema declares a `recordSource.endpoint`, that custom URL is used
 * and public pid placeholders are interpolated with the URL-encoded record pid.
 * This unblocks `skipTableCreation` models (e.g.
 * `ab_qr_code`) whose reads are served by a custom REST endpoint and would
 * otherwise 400/500 against the generic `/api/dynamic/<model>/<id>` route.
 *
 * Falls back to the default `/api/dynamic/<tableName>/<recordPid>` when no
 * `recordSource` is configured — preserving 100 % backward compatibility.
 */
export function replaceRecordEndpointPlaceholders(template: string, recordPid: string): string {
  const encoded = encodeURIComponent(recordPid);
  const legacyRecordKey = 'record' + 'Id';
  return template
    .replace(/\$\{recordPid\}|\{recordPid\}|\$\{pid\}|\{pid\}/g, encoded)
    .replace(new RegExp(`\\$\\{${legacyRecordKey}\\}|\\{${legacyRecordKey}\\}`, 'g'), encoded);
}

export function resolveEditRecordEndpoint(
  schema:
    | {
        modelCode?: string;
        recordSource?: { endpoint?: string; method?: string };
        extension?: { recordSource?: { endpoint?: string; method?: string } };
      }
    | null
    | undefined,
  tableName: string,
  recordPid?: string,
): string {
  const custom = schema?.recordSource?.endpoint || schema?.extension?.recordSource?.endpoint;
  if (custom && custom.trim()) {
    if (recordPid) {
      return replaceRecordEndpointPlaceholders(custom, recordPid);
    }
    return custom.replace(/\/+$/, '');
  }
  // Page-registry records live behind the page-manager REST resource, not the
  // dynamic model route — there is no `page_schema` dynamic model, so the
  // default below 400s with "Model not found: page_schema".
  if ((schema?.modelCode || tableName) === 'page_schema' && recordPid) {
    return `/api/pages/${encodeURIComponent(recordPid)}`;
  }
  if (!recordPid) {
    return `/api/dynamic/${schema?.modelCode || tableName}`;
  }
  return `/api/dynamic/${schema?.modelCode || tableName}/${recordPid}`;
}

export function isSingletonRecordSource(schema: any): boolean {
  return (
    schema?.recordSource?.mode === 'singleton' ||
    schema?.recordSource?.singleton === true ||
    schema?.extension?.recordSource?.mode === 'singleton' ||
    schema?.extension?.recordSource?.singleton === true ||
    schema?.extension?.recordMode === 'singleton' ||
    schema?.extension?.editMode === true
  );
}

export function shouldLoadMainRecordForForm(
  recordPid: string | null | undefined,
  schema: any,
): boolean {
  return Boolean(recordPid || isSingletonRecordSource(schema));
}

export function replaceSubmitEndpointPlaceholders(
  template: string,
  recordPid: string | null | undefined,
  data: Record<string, any>,
): string {
  const pid = recordPid || data.pid || data.recordPid || data.id || '';
  if (pid) {
    return replaceRecordEndpointPlaceholders(template, String(pid));
  }
  return template.replace(/\/+$/, '');
}

/** Pure model forms use public record identity to resolve save/submit into CRUD. */
export function resolveDynamicCrudFormOperation(
  actionType: string,
  recordPid: string | null | undefined,
): 'create' | 'update' | null {
  if (actionType === 'create' || actionType === 'update') return actionType;
  if (actionType === 'save' || actionType === 'submit') return recordPid ? 'update' : 'create';
  return null;
}

export function resolveFormSubmitEndpoint(
  schema: any,
  recordPid: string | null | undefined,
  data: Record<string, any>,
): { endpoint: string; method: 'post' | 'put' | 'patch' } | null {
  const config = schema?.extension?.submitEndpoint;
  if (!config || config.type !== 'api' || typeof config.endpoint !== 'string') {
    return null;
  }
  const rawMethod = String(config.method || (recordPid || data.pid ? 'put' : 'post')).toLowerCase();
  const method = rawMethod === 'patch' ? 'patch' : rawMethod === 'put' ? 'put' : 'post';
  return {
    endpoint: replaceSubmitEndpointPlaceholders(config.endpoint, recordPid, data),
    method,
  };
}

export function inferEditCommandCode(
  commandCode: string | null,
  isEditMode: boolean,
): string | null {
  if (!isEditMode || !commandCode) return commandCode;
  if (commandCode.includes(':create_')) {
    return commandCode.replace(':create_', ':update_');
  }
  if (commandCode.startsWith('create_')) {
    return commandCode.replace(/^create_/, 'update_');
  }
  return commandCode;
}

/**
 * Resolve the submit command for a form, convention over configuration.
 *
 * An explicit command (URL `?commandCode=`, the form button's command, or the
 * action object's command — already passed through {@link inferEditCommandCode})
 * always wins. When none is provided, fall back to the model's CRUD command that
 * the server resolved onto `schema.commands`: `update` when editing (a record id
 * is present), `create` when new. Returns null when neither is available (a pure
 * CRUD model), so the caller persists via the dynamic CRUD API instead.
 */
export function resolveSubmitCommandCode(
  explicitCommandCode: string | null,
  schemaCommands: Record<string, string> | undefined,
  isEditMode: boolean,
): string | null {
  if (explicitCommandCode) return explicitCommandCode;
  return schemaCommands?.[isEditMode ? 'update' : 'create'] ?? null;
}

export function resolveCreateSubTableEmptyState(
  config:
    | {
        emptyState?: {
          title?: string | Record<string, string>;
          description?: string | Record<string, string>;
        };
      }
    | null
    | undefined,
  locale: string,
  t: (key: string) => string,
): { title: string; description: string } {
  const chinese = locale.toLowerCase().startsWith('zh');
  const translatedDescription = t('common.saveFirstToAddLines');
  const fallbackDescription =
    translatedDescription !== 'common.saveFirstToAddLines'
      ? translatedDescription
      : chinese
        ? '请先保存当前单据，再到详情页添加业务明细。'
        : 'Save this record first, then add business lines on its detail page.';

  return {
    title: config?.emptyState?.title
      ? getLocalizedText(config.emptyState.title as any, locale, t)
      : chinese
        ? '明细将在保存后登记'
        : 'Lines become available after save',
    description: config?.emptyState?.description
      ? getLocalizedText(config.emptyState.description as any, locale, t)
      : fallbackDescription,
  };
}
