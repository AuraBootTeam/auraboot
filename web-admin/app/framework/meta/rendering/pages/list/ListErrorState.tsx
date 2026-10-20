import React from 'react';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { ErrorAlert } from '~/ui/ErrorAlert';

export interface ListErrorStateProps {
  title: string;
  error: string;
  errorTitle: string;
  retryLabel: string;
  buttons: ButtonConfig[];
  evaluateVisible: (button: ButtonConfig) => boolean;
  resolveLabel: (button: ButtonConfig) => string;
  onNavigate: (to: string) => void;
  onRetry: () => void;
}

/** Failed records cannot supply navigation context or authorize side effects. */
export function staticErrorNavigationTarget(button: ButtonConfig): string | undefined {
  if (button.disabled || button.enableWhen || button.disableWhen || button.commandCode !== undefined
      || button.navigateTo !== undefined || button.handler !== undefined || button.events !== undefined
      || button.apiAction !== undefined || button.reportCode !== undefined || button.preset !== undefined
      || button.confirm !== undefined || button.confirmMessageKey !== undefined) return undefined;
  const action = button.action;
  if (!action || typeof action !== 'object' || action.type !== 'navigate'
      || Object.keys(action).some(key => key !== 'type' && key !== 'to')) return undefined;
  const to = action.to;
  // Literal internal UI paths only: no templates, encoding, external origins or API endpoints.
  if (typeof to !== 'string' || to.split(/[?#]/, 1)[0].includes('//') || /^\/api(?:\/|\?|$)/i.test(to)
      || !/^\/[A-Za-z0-9/_-]+(?:\?[A-Za-z0-9._~=&-]+)?(?:#[A-Za-z0-9._~-]+)?$/.test(to)) return undefined;
  return to;
}

export function ListErrorState({ title, error, errorTitle, retryLabel, buttons,
  evaluateVisible, resolveLabel, onNavigate, onRetry }: ListErrorStateProps) {
  const navigation = buttons.flatMap(button => {
    const to = staticErrorNavigationTarget(button);
    return to && evaluateVisible(button) ? [{ button, to }] : [];
  });
  return (
    <div className="bg-subtle min-h-[calc(100vh-3.5rem)] w-full px-4 py-5 sm:px-6 lg:px-8">
      <section className="rounded-card border-border bg-panel overflow-hidden border shadow-sm"
        data-testid="list-error-shell" aria-label={title}>
        <header className="border-border flex flex-wrap items-center justify-between gap-3 border-b px-6 py-4">
          <h2 className="text-text text-xl font-semibold tracking-tight">{title}</h2>
          <div className="flex flex-wrap items-center gap-2">
            {navigation.map(({ button, to }) => (
              <button key={button.code} type="button"
                className="rounded-control border-border text-text hover:bg-subtle border px-3.5 py-2 text-sm"
                onClick={() => {
                  // Recheck the same permission/visibility gate at activation; never dispatch actions.
                  if (evaluateVisible(button)) onNavigate(to);
                }}>
                {resolveLabel(button)}
              </button>
            ))}
          </div>
        </header>
        <div role="alert">
          <ErrorAlert error={error} title={errorTitle} retryLabel={retryLabel} onRetry={onRetry} />
        </div>
      </section>
    </div>
  );
}
