/**
 * core-aurabot implementation of the framework's DslFormFillBackend contract.
 *
 * `extract` is statically available (it talks to the conversation API
 * directly, matching the previous behavior where extraction worked even
 * without the AuraBotProvider mounted). `registerTarget` binds to the
 * provider's live register callback when the provider mounts.
 */
import type { DslFormFillBackend, DslFormFillStreamHandlers } from '~/framework/meta/rendering/dslFormFillBackend';
import type { FormFillTarget } from '~/framework/meta/rendering/formFill';
import { auraBotApi } from './services/auraBotApi';

let liveRegister: ((target: FormFillTarget) => () => void) | undefined;

/** Called by AuraBotProvider on mount; the returned function unbinds on unmount. */
export function bindFormFillRegister(register: (target: FormFillTarget) => () => void): () => void {
  liveRegister = register;
  return () => {
    liveRegister = undefined;
  };
}

export const dslFormFillBackend: DslFormFillBackend = {
  registerTarget: (target) => liveRegister?.(target),
  extract: async (request, handlers: DslFormFillStreamHandlers) => {
    const conversation = await auraBotApi.ensureConversation('aurabot');
    await auraBotApi.chatStream(
      {
        sessionId: crypto.randomUUID(),
        message: request.message,
        agentCode: 'aurabot',
        conversationId: conversation.conversationId,
        clientMsgId: crypto.randomUUID(),
        formFill: request.formFill as any,
        pageContext: request.pageContext as any,
      },
      handlers as any,
    );
  },
};
