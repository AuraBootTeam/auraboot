import type { ReactElement } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import type {
  WizardProgressEnvelope as WizardProgressEnvelopeData,
} from '../../types/envelope';

export function WizardProgressEnvelope({
  envelope,
}: {
  envelope: WizardProgressEnvelopeData;
}): ReactElement {
  const { t } = useI18n();
  const text = t(
    'aurabot.shell.wizard.progress',
    { step: envelope.step, total: envelope.total, label: envelope.label },
    `Step ${envelope.step} of ${envelope.total}: ${envelope.label}`,
  );
  const pct = Math.max(
    0,
    Math.min(100, envelope.total > 0 ? (envelope.step / envelope.total) * 100 : 0),
  );

  return (
    <div
      data-aurabot-envelope="wizard-progress"
      className="rounded-md border border-blue-200 bg-blue-50 p-2 text-xs text-blue-900 dark:border-blue-800 dark:bg-blue-950/30 dark:text-blue-100"
    >
      <div className="mb-1 font-medium">{text}</div>
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-blue-100 dark:bg-blue-900/50">
        <div
          className="h-full bg-blue-500 transition-all"
          style={{ width: `${pct}%` }}
          aria-valuenow={envelope.step}
          aria-valuemin={0}
          aria-valuemax={envelope.total}
          role="progressbar"
        />
      </div>
    </div>
  );
}
