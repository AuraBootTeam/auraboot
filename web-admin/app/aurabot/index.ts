/**
 * Public surface of the AuraBot V3 shell.
 *
 * Naming: every export is suffixed with `Shell` to keep V3 disambiguated
 * from the legacy V2 surface in `~/plugins/core-aurabot/components-shell`.
 */

export { AuraBotShellProvider, useAuraBotShell } from './AuraBotProvider';
export { AuraBotShellPanel } from './AuraBotPanel';
export { AuraBotShellToggle } from './AuraBotToggle';
export type { PanelState } from './types/panel';
export type { Envelope, EnvelopeKind, Message } from './types/envelope';
export type {
  AgentContext,
  RiskLevel,
  SkillError,
  SkillMeta,
  SkillRequest,
  SkillResult,
  SkillStatus,
  SkillSuggestion,
} from './types/skill';
