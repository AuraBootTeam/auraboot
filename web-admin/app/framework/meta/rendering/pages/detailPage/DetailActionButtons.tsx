import type { ReactNode } from 'react';
import { isButtonDisabled } from '~/framework/meta/utils/buttonPermissions';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { buttonTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { canRenderDetailToolbarButton } from './detailLayoutValues';
import type { RecordData } from './detailPageTypes';

export interface DetailActionButtonsProps {
  placement?: 'header' | 'footer';
  overflowUtilities?: ReactNode;
  buttons: Array<ButtonConfig & { overflow?: boolean }>;
  modelCode: string;
  recordData: RecordData;
  hasPermission: (permission: string) => boolean;
  evaluateVisibleWhen: (expression: string | undefined) => boolean;
  actionLoading: boolean;
  resolveButtonLabel: (button: ButtonConfig) => string;
  handleAction: (button: ButtonConfig, record?: Record<string, any>) => Promise<void>;
  locale: string;
  t: (key: string) => string;
}

/** Shared header/footer action renderer; dispatch remains in useActionHandler. */
export function DetailActionButtons({
  buttons,
  modelCode,
  recordData,
  hasPermission,
  evaluateVisibleWhen,
  actionLoading,
  resolveButtonLabel,
  handleAction,
  locale,
  t,
  placement = 'header',
  overflowUtilities,
}: DetailActionButtonsProps) {
  const visibleButtons = buttons.filter(
    (button) =>
      canRenderDetailToolbarButton(button, hasPermission) &&
      evaluateVisibleWhen(button.visibleWhen),
  );
  const overflowButtons = visibleButtons.filter((button) => button.overflow);
  const moreLabel = getLocalizedText({ 'zh-CN': '更多操作', en: 'More actions' }, locale, t);
  const isFooter = placement === 'footer';
  return (
    <>
      {visibleButtons
        .filter((button) => !button.overflow)
        .map((button) => (
          <button
            key={button.code}
            data-testid={`toolbar-btn-${button.code}`}
            data-ab-testid={buttonTestId('detail', modelCode, button.code)}
            onClick={() => handleAction(button, recordData)}
            disabled={isButtonDisabled(button, evaluateVisibleWhen, actionLoading)}
            className={`rounded-control px-3 py-1.5 text-sm font-medium whitespace-nowrap ${
              button.primary
                ? 'bg-accent hover:bg-accent-hover text-white'
                : button.danger
                  ? 'bg-status-red hover:bg-status-red/90 text-white'
                  : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
            } disabled:cursor-not-allowed disabled:opacity-50`}
          >
            {resolveButtonLabel(button)}
          </button>
        ))}
      {(overflowButtons.length > 0 || overflowUtilities) && (
        <details className={isFooter ? 'relative' : 'sm:relative'} data-testid="detail-toolbar-more">
          <summary
            aria-label={moreLabel}
            className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-hover cursor-pointer list-none whitespace-nowrap border px-3 py-1.5 text-sm font-medium"
          >
            {moreLabel}
          </summary>
          <div
            data-testid="detail-toolbar-more-menu"
            className={`border-border bg-panel rounded-card absolute right-0 z-40 max-w-[min(20rem,75vw)] min-w-40 border p-1 shadow-lg ${isFooter ? 'bottom-full mb-2' : 'top-10 max-sm:top-full max-sm:mt-2'}`}
          >
            {overflowButtons.length > 0 && (
              <div className="max-h-[50vh] overflow-y-auto">
                {overflowButtons.map((button) => (
                  <button
                    key={button.code}
                    type="button"
                    data-testid={`toolbar-btn-${button.code}`}
                    disabled={isButtonDisabled(button, evaluateVisibleWhen, actionLoading)}
                    className={`hover:bg-hover block w-full rounded px-3 py-2 text-left text-xs ${button.danger ? 'text-status-red' : 'text-text-2'}`}
                    onClick={(event) => {
                      const menu = event.currentTarget.closest('details');
                      if (menu) menu.open = false;
                      void handleAction(button, recordData);
                    }}
                  >
                    {resolveButtonLabel(button)}
                  </button>
                ))}
              </div>
            )}
            {overflowUtilities && (
              <div className={`flex w-max max-w-full flex-wrap gap-2 p-1 ${overflowButtons.length ? 'border-border mt-1 border-t pt-2' : ''}`}>
                {overflowUtilities}
              </div>
            )}
          </div>
        </details>
      )}
    </>
  );
}
