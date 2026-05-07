import type { ReactElement } from 'react';
import type { Envelope } from '../../types/envelope';
import type { SkillSuggestion } from '../../types/skill';
import { TextEnvelope } from './TextEnvelope';
import { ThinkingEnvelope } from './ThinkingEnvelope';
import { PreviewEnvelope } from './PreviewEnvelope';
import { ResultEnvelope } from './ResultEnvelope';
import { ConfirmEnvelope } from './ConfirmEnvelope';
import { SuggestionEnvelope } from './SuggestionEnvelope';
import { WizardProgressEnvelope } from './WizardProgressEnvelope';
import { ErrorEnvelope } from './ErrorEnvelope';
import { CodeEnvelope } from './CodeEnvelope';

export interface EnvelopeRouterProps {
  envelope: Envelope;
  onConfirm?: (previewToken: string) => void;
  onCancel?: (previewToken: string) => void;
  onSuggestionPick?: (suggestion: SkillSuggestion) => void;
}

/** Routes a single envelope to its presentational component. */
export function EnvelopeRouter(props: EnvelopeRouterProps): ReactElement {
  const { envelope, onConfirm, onCancel, onSuggestionPick } = props;
  switch (envelope.kind) {
    case 'text':
      return <TextEnvelope envelope={envelope} />;
    case 'thinking':
      return <ThinkingEnvelope envelope={envelope} />;
    case 'preview':
      return <PreviewEnvelope envelope={envelope} />;
    case 'result':
      return <ResultEnvelope envelope={envelope} />;
    case 'confirm':
      return (
        <ConfirmEnvelope envelope={envelope} onConfirm={onConfirm} onCancel={onCancel} />
      );
    case 'suggestion':
      return <SuggestionEnvelope envelope={envelope} onPick={onSuggestionPick} />;
    case 'wizard-progress':
      return <WizardProgressEnvelope envelope={envelope} />;
    case 'error':
      return <ErrorEnvelope envelope={envelope} />;
    case 'code':
      return <CodeEnvelope envelope={envelope} />;
    default: {
      // exhaustiveness check
      const _never: never = envelope;
      return <></>;
    }
  }
}
