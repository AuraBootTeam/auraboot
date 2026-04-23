import type { ButtonConfig, ActionDef, FlowStep } from '~/framework/meta/schemas/types';

const BUILTIN_CODES = new Set([
  'search',
  'reset',
  'refresh',
  'export',
  'new',
  'edit',
  'view',
  'delete',
  'back',
  'cancel',
  'noop',
]);

let warnedKeys = new Set<string>();

function warnOnce(key: string, msg: string) {
  if (warnedKeys.has(key)) return;
  warnedKeys.add(key);
  console.warn(`[DSL Deprecation] ${msg}`);
}

export function normalizeAction(button: ButtonConfig): ActionDef {
  // New format: pass through
  if (button.action && typeof button.action === 'object' && 'type' in button.action) {
    return button.action as ActionDef;
  }

  // Legacy: commandCode + navigateTo
  if (button.commandCode && button.navigateTo) {
    warnOnce(
      `${button.code}:cmd+nav`,
      `Button "${button.code}": Use action: { type: "navigate", to, command }`,
    );
    return { type: 'navigate', to: button.navigateTo, command: button.commandCode };
  }

  // Legacy: commandCode only
  if (button.commandCode) {
    warnOnce(
      `${button.code}:cmd`,
      `Button "${button.code}": Use action: { type: "command", command }`,
    );
    return { type: 'command', command: button.commandCode };
  }

  // Legacy: apiAction
  if (button.apiAction) {
    warnOnce(
      `${button.code}:api`,
      `Button "${button.code}": Use action: { type: "flow", steps: [...] }`,
    );
    const steps: FlowStep[] = [
      {
        action: 'api.request',
        endpoint: button.apiAction.endpoint,
        method: button.apiAction.method || 'post',
      },
    ];
    if (button.apiAction.successMessage) {
      steps.push({ action: 'toast.success', args: { message: button.apiAction.successMessage } });
    }
    steps.push({ action: 'dataSource.reload', args: { target: 'list' } });
    return { type: 'flow', steps };
  }

  // Legacy: navigateTo only
  if (button.navigateTo) {
    warnOnce(`${button.code}:nav`, `Button "${button.code}": Use action: { type: "navigate", to }`);
    return { type: 'navigate', to: button.navigateTo };
  }

  // Legacy: events.onClick.handler
  if (button.events?.onClick?.handler) {
    const handler = button.events.onClick.handler;
    warnOnce(
      `${button.code}:handler`,
      `Button "${button.code}": Use action: { type: "flow", handler }`,
    );
    if (handler === 'navigateBack') {
      return { type: 'builtin', name: 'back' };
    }
    return { type: 'flow', handler };
  }

  // code → builtin
  if (button.code && BUILTIN_CODES.has(button.code)) {
    return { type: 'builtin', name: button.code };
  }

  return { type: 'builtin', name: 'noop' };
}

export function normalizeButtonProps(button: ButtonConfig): ButtonConfig {
  const result = { ...button };
  // action (string i18n key) → label
  if (typeof button.action === 'string') {
    if (!result.label) {
      result.label = button.action;
    }
  }
  // confirmMessageKey → confirm
  if (button.confirmMessageKey && !result.confirm) {
    result.confirm = button.confirmMessageKey;
  }
  return result;
}

export function _resetWarnings() {
  warnedKeys = new Set();
}
