/**
 * Client-side EventPolicy preview, mirroring the backend
 * {@code com.auraboot.framework.eventpolicy.runtime.EventPolicyEvaluator} (docs/2.md §5-§6): given a
 * policy's matchMode + rules + a context, determine which rules match — under three-valued condition
 * evaluation (a disabled rule is skipped). Preview only; the backend stays authoritative.
 */
import { type ConditionNode, type ScopedContext, evaluatePreview } from '../ast/conditionAst';

export type MatchMode = 'FIRST_MATCH' | 'COLLECT_ALL' | 'UNIQUE' | 'PRIORITY_FIRST';

export interface PreviewRule {
  ruleCode: string;
  priority?: number;
  enabled?: boolean;
  condition: ConditionNode;
}

export interface PolicyPreviewInput {
  matchMode: MatchMode;
  rules: PreviewRule[];
}

export type PolicyPreviewStatus = 'MATCHED' | 'NOT_MATCHED' | 'ERROR';

export interface PolicyPreviewResult {
  status: PolicyPreviewStatus;
  matchedRuleCodes: string[];
  skippedRuleCodes: string[];
  error?: string;
}

/** Evaluate which rules match, honoring matchMode (mirrors EventPolicyEvaluator). */
export function evaluatePolicyPreview(policy: PolicyPreviewInput, ctx: ScopedContext): PolicyPreviewResult {
  const ordered = policy.rules.slice().sort((a, b) => (a.priority ?? 0) - (b.priority ?? 0));
  const skipped: string[] = [];
  const matched: string[] = [];

  for (const rule of ordered) {
    if (rule.enabled === false) {
      skipped.push(rule.ruleCode);
      continue;
    }
    const t = evaluatePreview(rule.condition, ctx);
    if (t === 'TRUE') {
      matched.push(rule.ruleCode);
      if (policy.matchMode === 'FIRST_MATCH' || policy.matchMode === 'PRIORITY_FIRST') {
        break; // first match in priority order wins
      }
    }
  }

  if (matched.length === 0) {
    return { status: 'NOT_MATCHED', matchedRuleCodes: [], skippedRuleCodes: skipped };
  }
  if (policy.matchMode === 'UNIQUE' && matched.length > 1) {
    return {
      status: 'ERROR', matchedRuleCodes: matched, skippedRuleCodes: skipped,
      error: `UNIQUE match mode matched ${matched.length} rules: ${matched.join(', ')}`,
    };
  }
  return { status: 'MATCHED', matchedRuleCodes: matched, skippedRuleCodes: skipped };
}
