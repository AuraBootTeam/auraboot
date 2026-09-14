/** Shared draft mutation boundary for every AI form-fill entry. */
import React, { createContext, useContext, useEffect, useMemo, useRef } from 'react';
import { useAuraBotSafe } from '~/plugins/core-aurabot/hooks/useAuraBotSafe';
import { auraBotApi } from '~/plugins/core-aurabot/services/auraBotApi';
import type { FormFillResult, FormFillTarget, FormFillReview } from './formFill';

export interface DslFormFillApi {
  applyFields: (fields: Record<string, unknown>) => FormFillResult;
  extractFromText: (text: string) => Promise<void>;
  lockedFields: string[];
}

const DslFormFillContext = createContext<DslFormFillApi | null>(null);

export function DslFormFillProvider({
  target,
  children,
}: {
  target: FormFillTarget;
  children: React.ReactNode;
}) {
  const auraBot = useAuraBotSafe();
  const register = auraBot?.registerFormFillTarget;
  const active = useRef<FormFillTarget | null>(target);
  const busy = useRef(false);
  active.current = target;
  useEffect(() => {
    active.current = target;
    const unregister = register?.(target);
    return () => {
      active.current = null;
      unregister?.();
    };
  }, [target, register]);
  const api = useMemo<DslFormFillApi>(
    () => ({
      get lockedFields() {
        return target
          .snapshot()
          .fields.filter((field) => field.locked)
          .map((field) => field.code);
      },
      applyFields: (fields) => {
        if (active.current !== target) throw new Error('ai.fill.target_changed');
        return target.apply(fields);
      },
      extractFromText: async (text) => {
        if (!text.trim()) throw new Error('ai.fill.nl_input_required');
        if (busy.current) throw new Error('ai.fill.busy');
        const formFill = target.snapshot();
        formFill.fields = formFill.fields.filter((field) => !field.locked);
        if (!formFill.fields.length) throw new Error('ai.fill.no_editable_fields');
        busy.current = true;
        let failure: string | undefined;
        let received = false;
        const proposals: Record<string, unknown> = Object.create(null);
        const reviews: Record<string, FormFillReview> = Object.create(null);
        const seen = new Set<string>();
        try {
          const conversation = await auraBotApi.ensureConversation('aurabot');
          await auraBotApi.chatStream(
            {
              sessionId: crypto.randomUUID(),
              message: text,
              agentCode: 'aurabot',
              conversationId: conversation.conversationId,
              clientMsgId: crypto.randomUUID(),
              formFill,
              pageContext: { kind: 'form', modelCode: formFill.modelCode },
            },
            {
              onChunk: () => {},
              onDone: () => {},
              onError: (error) => {
                failure = error;
              },
              onConfirmRequired: () => {
                failure = 'ai.fill.unexpected_action';
              },
              onToolResult: (id, result, success) => {
                if (seen.has(id)) return;
                seen.add(id);
                // Platform provider tool results are the raw output map on this SSE event.
                if (!success || result.action !== 'form_fill' || !result.fields) return;
                if (active.current !== target) {
                  failure = 'ai.fill.target_changed';
                  return;
                }
                if (
                  !result.reviews ||
                  Object.values(result.reviews).some(
                    (review: any) =>
                      typeof review?.quote !== 'string' ||
                      !review.quote.trim() ||
                      !text.includes(review.quote),
                  )
                ) {
                  failure = 'ai.fill.invalid_evidence';
                  return;
                }
                Object.assign(proposals, result.fields);
                Object.assign(reviews, result.reviews);
                received = Object.keys(proposals).length > 0 || Object.keys(reviews).length > 0;
              },
            },
          );
          if (failure) throw new Error(failure);
          if (!received) throw new Error('ai.fill.no_fields_extracted');
          if (active.current !== target) throw new Error('ai.fill.target_changed');
          target.apply(proposals, reviews);
        } finally {
          busy.current = false;
        }
      },
    }),
    [target],
  );
  return <DslFormFillContext.Provider value={api}>{children}</DslFormFillContext.Provider>;
}

export function useDslFormFill(): DslFormFillApi {
  const context = useContext(DslFormFillContext);
  if (!context) throw new Error('AI fill requires an active form');
  return context;
}
