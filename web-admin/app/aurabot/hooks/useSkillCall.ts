/**
 * useSkillCall — wires (collectContext → execute → mapper → appendMessage).
 *
 * Loads the appropriate SkillClient (real HTTP or mock) on first call. The
 * resolution result is memoised so a single panel session uses one client
 * for its entire lifetime.
 *
 * Returns a stable `send(text)` callback plus a busy flag so InputArea can
 * disable while a request is in flight.
 */

import { useCallback, useRef, useState } from 'react';
import { useLocation } from 'react-router';
import { useAuraBotShell } from '../AuraBotProvider';
import {
  httpSkillClient,
  resolveSkillClient,
  type SkillClient,
} from '../services/skillClient';
import {
  collectContext,
  inferModelCodeFromRoute,
} from '../services/contextCollector';
import { mapSkillResultToEnvelopes } from '../services/envelopeMapper';
import type { Message } from '../types/envelope';
import type { SkillRequest } from '../types/skill';

let _clientPromise: Promise<SkillClient> | null = null;

function getClient(): Promise<SkillClient> {
  if (!_clientPromise) {
    _clientPromise = resolveSkillClient().catch((err) => {
      // Fallback to httpSkillClient if dynamic import of the mock fails for
      // any reason — better than blocking the panel entirely.
      // eslint-disable-next-line no-console
      console.warn('[aurabot] resolveSkillClient failed, falling back to http', err);
      return httpSkillClient;
    });
  }
  return _clientPromise;
}

let idemCounter = 0;
const nextIdempotencyKey = () => `fe_${Date.now()}_${++idemCounter}`;
const nextMessageId = () =>
  `msg_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

export interface SkillCallOptions {
  /**
   * Default skill to invoke. The C-1 shell wires this to `echo`; specific
   * skill specs (C-3+) replace it with their own intent-resolved skill name.
   */
  skillName?: string;
}

export interface SkillCallApi {
  send: (text: string) => Promise<void>;
  busy: boolean;
}

export function useSkillCall(options: SkillCallOptions = {}): SkillCallApi {
  const { skillName = 'echo' } = options;
  const { appendMessage } = useAuraBotShell();
  const location = useLocation();
  const [busy, setBusy] = useState(false);
  const inFlightRef = useRef(false);

  const send = useCallback(
    async (text: string) => {
      if (inFlightRef.current) return;
      inFlightRef.current = true;
      setBusy(true);

      // Echo the user input first so the panel feels responsive.
      const userMessage: Message = {
        id: nextMessageId(),
        role: 'user',
        envelopes: [{ kind: 'text', text }],
      };
      appendMessage(userMessage);

      const route = location.pathname;
      const request: SkillRequest = {
        skillName,
        params: { text },
        context: collectContext({
          route,
          modelCode: inferModelCodeFromRoute(route),
          pageId: null,
        }),
        idempotencyKey: nextIdempotencyKey(),
        previewToken: null,
      };

      try {
        const client = await getClient();
        const result = await client.execute(request);
        const envelopes = mapSkillResultToEnvelopes(result);
        appendMessage({
          id: nextMessageId(),
          traceId: result.traceId,
          role: 'assistant',
          envelopes:
            envelopes.length > 0
              ? envelopes
              : [{ kind: 'text', text: '' }],
        });
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        appendMessage({
          id: nextMessageId(),
          role: 'assistant',
          envelopes: [
            {
              kind: 'error',
              code: 'SKILL_TRANSPORT_ERROR',
              message,
            },
          ],
        });
      } finally {
        inFlightRef.current = false;
        setBusy(false);
      }
    },
    [appendMessage, location.pathname, skillName],
  );

  return { send, busy };
}
