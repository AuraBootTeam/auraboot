import { useCallback } from 'react';
import type { LinkageRule, LinkageAction } from '~/studio/workbench/panels/linkage/types';
import { createLinkageRule } from '~/studio/workbench/panels/linkage/types';
import { useDesignerStore } from '~/studio/hooks/store/useDesignerStore';

/**
 * Hook for managing linkage rules in the designer.
 * Rules are persisted in PageSchema.linkageRules via useDesignerStore.
 *
 * @since 3.5.0
 */
export function useLinkageRules() {
  const pageSchema = useDesignerStore((state) => state.pageSchema);
  const { updatePageSchema } = useDesignerStore();

  const rules: LinkageRule[] = pageSchema?.linkageRules ?? [];

  // Local helper to update linkageRules in the schema
  const setRules = useCallback(
    (updater: (prev: LinkageRule[]) => LinkageRule[]) => {
      updatePageSchema((draft) => {
        draft.linkageRules = updater(draft.linkageRules ?? []);
      });
    },
    [updatePageSchema],
  );

  const selectedRuleId = useDesignerStore((state) => state.linkageSelectedRuleId);
  const setLinkageSelectedRuleId = useDesignerStore((state) => state.setLinkageSelectedRuleId);
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
