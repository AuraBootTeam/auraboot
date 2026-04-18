/**
 * Safe version of useAuraBot that returns null when outside AuraBotProvider.
 * Use in components (e.g. DslFormRenderer) that may render with or without
 * AuraBot context.
 *
 * Previously this was an OSS "slot stub" returning a NOOP handle while the
 * real implementation lived in the enterprise ent-aurabot-pro overlay. Per
 * the 2026-04-15 OSS expansion rule (AI 全栈全部开源), the real hook now
 * lives here.
 */
import { useContext } from 'react';
import { AuraBotCtx } from '~/plugins/core-aurabot/components-shell/AuraBotProvider';
import type { AuraBotContextValue } from '~/plugins/core-aurabot/components-shell/AuraBotProvider';

export function useAuraBotSafe(): AuraBotContextValue | null {
  return useContext(AuraBotCtx);
}
