import type { ReactElement } from 'react';
import type { TextEnvelope as TextEnvelopeData } from '../../types/envelope';

export function TextEnvelope({ envelope }: { envelope: TextEnvelopeData }): ReactElement {
  return (
    <div
      className="whitespace-pre-wrap break-words text-sm leading-relaxed text-gray-900 dark:text-gray-100"
      data-aurabot-envelope="text"
    >
      {envelope.text}
    </div>
  );
}
