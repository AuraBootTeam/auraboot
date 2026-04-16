import { useCallback } from 'react';
import type { LinkageRule, LinkageAction } from '~/plugins/core-designer/components/studio/workbench/panels/linkage/types';
import { createLinkageRule } from '~/plugins/core-designer/components/studio/workbench/panels/linkage/types';
import { useCanvasEditorState } from '~/plugins/core-designer/components/studio/hooks/store/useCanvasEditorState';
import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

export interface UseLinkageRulesOptions {
  /** Current page schema (schema half) */
  schema: FormSchema;
  /** Called when linkageRules should be updated in the schema */
  onSchemaChange: (next: FormSchema) => void;
}

/**
 * Hook for managing linkage rules in the designer.
 * The schema half flows in via props; UI selection state lives in useCanvasEditorState.
 *
 * @since 3.5.0
 */
export function useLinkageRules({ schema, onSchemaChange }: UseLinkageRulesOptions) {
  const rules: LinkageRule[] = schema?.linkageRules ?? [];

  // Local helper to update linkageRules in the schema
  const setRules = useCallback(
    (updater: (prev: LinkageRule[]) => LinkageRule[]) => {
      onSchemaChange({
        ...schema,
        linkageRules: updater(schema.linkageRules ?? []),
      });
    },
    [schema, onSchemaChange],
  );

  const selectedRuleId = useCanvasEditorState((state) => state.linkageSelectedRuleId);
  const setLinkageSelectedRuleId = useCanvasEditorState((state) => state.setLinkageSelectedRuleId);
  const setSelectedRuleId = useCallback(
    (id: string | null) => {
      setLinkageSelectedRuleId(id);
    },
    [setLinkageSelectedRuleId],
  );

  const addRule = useCallback(() => {
    const newRule = createLinkageRule();
    setRules((prev) => [...prev, newRule]);
    setSelectedRuleId(newRule.id);
    return newRule;
  }, [setRules, setSelectedRuleId]);

  const removeRule = useCallback(
    (ruleId: string) => {
      setRules((prev) => prev.filter((r) => r.id !== ruleId));
      setSelectedRuleId(selectedRuleId === ruleId ? null : selectedRuleId);
    },
    [setRules, selectedRuleId, setSelectedRuleId],
  );

  const updateRule = useCallback(
    (ruleId: string, updates: Partial<LinkageRule>) => {
      setRules((prev) => prev.map((r) => (r.id === ruleId ? { ...r, ...updates } : r)));
    },
    [setRules],
  );

  const updateTrigger = useCallback(
    (ruleId: string, trigger: Partial<LinkageRule['trigger']>) => {
      setRules((prev) =>
        prev.map((r) => (r.id === ruleId ? { ...r, trigger: { ...r.trigger, ...trigger } } : r)),
      );
    },
    [setRules],
  );

  const addAction = useCallback(
    (ruleId: string, action: LinkageAction) => {
      setRules((prev) =>
        prev.map((r) => (r.id === ruleId ? { ...r, actions: [...r.actions, action] } : r)),
      );
    },
    [setRules],
  );

  const removeAction = useCallback(
    (ruleId: string, actionIndex: number) => {
      setRules((prev) =>
        prev.map((r) =>
          r.id === ruleId ? { ...r, actions: r.actions.filter((_, i) => i !== actionIndex) } : r,
        ),
      );
    },
    [setRules],
  );

  const updateAction = useCallback(
    (ruleId: string, actionIndex: number, updates: Partial<LinkageAction>) => {
      setRules((prev) =>
        prev.map((r) => {
          if (r.id !== ruleId) return r;
          const newActions = [...r.actions];
          newActions[actionIndex] = { ...newActions[actionIndex], ...updates } as LinkageAction;
          return { ...r, actions: newActions };
        }),
      );
    },
    [setRules],
  );

  const toggleRuleEnabled = useCallback(
    (ruleId: string) => {
      setRules((prev) => prev.map((r) => (r.id === ruleId ? { ...r, enabled: !r.enabled } : r)));
    },
    [setRules],
  );

  const duplicateRule = useCallback(
    (ruleId: string) => {
      setRules((prev) => {
        const source = prev.find((r) => r.id === ruleId);
        if (!source) return prev;
        const dup: LinkageRule = {
          ...source,
          id: crypto.randomUUID(),
          name: source.name ? `${source.name} (副本)` : undefined,
        };
        return [...prev, dup];
      });
    },
    [setRules],
  );

  const reorderRules = useCallback(
    (fromIndex: number, toIndex: number) => {
      setRules((prev) => {
        const next = [...prev];
        const [moved] = next.splice(fromIndex, 1);
        next.splice(toIndex, 0, moved);
        return next;
      });
    },
    [setRules],
  );

  const selectedRule = rules.find((r) => r.id === selectedRuleId) ?? null;

  return {
    rules,
    selectedRule,
    selectedRuleId,
    setSelectedRuleId,
    addRule,
    removeRule,
    updateRule,
    updateTrigger,
    addAction,
    removeAction,
    updateAction,
    toggleRuleEnabled,
    duplicateRule,
    reorderRules,
  };
}
