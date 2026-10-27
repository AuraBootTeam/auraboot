import { isButtonDisabled } from '~/framework/meta/utils/buttonPermissions';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import type { ButtonConfig } from '~/framework/meta/schemas/types';
import { buttonTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { canRenderDetailToolbarButton } from './detailLayoutValues';
import type { RecordData } from './detailPageTypes';

export interface DetailActionButtonsProps {
  placement?: 'header' | 'footer';
  buttons: ButtonConfig[];
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
}: DetailActionButtonsProps) {
  return (
    <>
      {buttons
        .filter((button: ButtonConfig) => canRenderDetailToolbarButton(button, hasPermission))
        .filter((button: ButtonConfig) => evaluateVisibleWhen(button.visibleWhen))
        .filter((button: ButtonConfig) => !(button as any).overflow)
        .map((button: ButtonConfig) => (
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
      {buttons.some(
        (button: ButtonConfig) =>
          (button as any).overflow &&
          canRenderDetailToolbarButton(button, hasPermission) &&
          evaluateVisibleWhen(button.visibleWhen),
      ) && (
        <details className="relative" data-testid="detail-toolbar-more">
          <summary
            aria-label={getLocalizedText({ 'zh-CN': '更多操作', en: 'More actions' }, locale, t)}
            className="text-text-2 hover:bg-hover rounded-control flex h-8 w-8 cursor-pointer list-none items-center justify-center text-xl"
          >
            ⋮
          </summary>
          <div
            data-testid="detail-toolbar-more-menu"
            className={`border-border bg-panel rounded-card absolute right-0 z-40 max-h-[60vh] min-w-40 overflow-y-auto border p-1 shadow-lg ${placement === 'footer' ? 'bottom-full mb-2' : 'top-10'}`}
          >
            {buttons
              .filter(
                (button: ButtonConfig) =>
                  (button as any).overflow &&
                  canRenderDetailToolbarButton(button, hasPermission) &&
                  evaluateVisibleWhen(button.visibleWhen),
              )
              .map((button: ButtonConfig) => (
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
        </details>
      )}
    </>
  );
}
