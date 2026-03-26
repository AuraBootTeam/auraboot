/**
 * Command Actions Hook
 *
 * Loads command definitions from the backend for a given model,
 * and manages ActionConfig state for the designer.
 *
 * @since 3.3.0
 */

import { useState, useEffect, useCallback } from 'react';
import { commandActionService } from '~/studio/services/command/CommandActionService';
import type {
  ActionConfig,
  ActionPhase,
  ActionPhaseType,
  CommandDefinitionDTO,
} from '~/studio/workbench/panels/actions/types';
import {
  createDefaultActionConfig,
  createActionPhase,
} from '~/studio/workbench/panels/actions/types';

export interface UseCommandActionsReturn {
  // Command data
  commands: CommandDefinitionDTO[];
  loadingCommands: boolean;
  commandsError: string | null;
  refreshCommands: () => void;

  // Action config management
  actions: ActionConfig[];
  selectedActionId: string | null;
  selectAction: (actionId: string | null) => void;
  addAction: (command: CommandDefinitionDTO) => void;
  removeAction: (actionId: string) => void;
  updateAction: (actionId: string, updates: Partial<ActionConfig>) => void;

  // Phase management
  addPhase: (
    actionId: string,
    category: keyof ActionConfig['phases'],
    type: ActionPhaseType,
  ) => void;
  removePhase: (actionId: string, category: keyof ActionConfig['phases'], phaseId: string) => void;
  updatePhase: (
    actionId: string,
    category: keyof ActionConfig['phases'],
    phaseId: string,
    updates: Partial<ActionPhase>,
  ) => void;
  movePhase: (
    actionId: string,
    category: keyof ActionConfig['phases'],
    phaseId: string,
    direction: 'up' | 'down',
  ) => void;
}

export function useCommandActions(modelCode?: string): UseCommandActionsReturn {
  const [commands, setCommands] = useState<CommandDefinitionDTO[]>([]);
  const [loadingCommands, setLoadingCommands] = useState(false);
  const [commandsError, setCommandsError] = useState<string | null>(null);
  const [actions, setActions] = useState<ActionConfig[]>([]);
  const [selectedActionId, setSelectedActionId] = useState<string | null>(null);

  // Load commands from backend
  const loadCommands = useCallback(async () => {
    if (!modelCode) {
      setCommands([]);
      return;
    }
    setLoadingCommands(true);
    setCommandsError(null);
    try {
      const data = await commandActionService.listByModelCode(modelCode);
      setCommands(data);
    } catch (err) {
      setCommandsError(err instanceof Error ? err.message : '加载命令失败');
    } finally {
      setLoadingCommands(false);
    }
  }, [modelCode]);

  useEffect(() => {
    loadCommands();
  }, [loadCommands]);

  // Action CRUD
  const addAction = useCallback((command: CommandDefinitionDTO) => {
    const newAction = createDefaultActionConfig(command);
    setActions((prev) => [...prev, newAction]);
    setSelectedActionId(newAction.id);
  }, []);

  const removeAction = useCallback((actionId: string) => {
    setActions((prev) => prev.filter((a) => a.id !== actionId));
    setSelectedActionId((prev) => (prev === actionId ? null : prev));
  }, []);

  const updateAction = useCallback((actionId: string, updates: Partial<ActionConfig>) => {
    setActions((prev) => prev.map((a) => (a.id === actionId ? { ...a, ...updates } : a)));
  }, []);

  const selectAction = useCallback((actionId: string | null) => {
    setSelectedActionId(actionId);
  }, []);

  // Phase management
  const addPhase = useCallback(
    (actionId: string, category: keyof ActionConfig['phases'], type: ActionPhaseType) => {
      setActions((prev) =>
        prev.map((a) => {
          if (a.id !== actionId) return a;
          return {
            ...a,
            phases: {
              ...a.phases,
              [category]: [...a.phases[category], createActionPhase(type)],
            },
          };
        }),
      );
    },
    [],
  );

  const removePhase = useCallback(
    (actionId: string, category: keyof ActionConfig['phases'], phaseId: string) => {
      setActions((prev) =>
        prev.map((a) => {
          if (a.id !== actionId) return a;
          return {
            ...a,
            phases: {
              ...a.phases,
              [category]: a.phases[category].filter((p) => p.id !== phaseId),
            },
          };
        }),
      );
    },
    [],
  );

  const updatePhase = useCallback(
    (
      actionId: string,
      category: keyof ActionConfig['phases'],
      phaseId: string,
      updates: Partial<ActionPhase>,
    ) => {
      setActions((prev) =>
        prev.map((a) => {
          if (a.id !== actionId) return a;
          return {
            ...a,
            phases: {
              ...a.phases,
              [category]: a.phases[category].map((p) =>
                p.id === phaseId ? { ...p, ...updates } : p,
              ),
            },
          };
        }),
      );
    },
    [],
  );

  const movePhase = useCallback(
    (
      actionId: string,
      category: keyof ActionConfig['phases'],
      phaseId: string,
      direction: 'up' | 'down',
    ) => {
      setActions((prev) =>
        prev.map((a) => {
          if (a.id !== actionId) return a;
          const phases = [...a.phases[category]];
          const idx = phases.findIndex((p) => p.id === phaseId);
          if (idx === -1) return a;
          const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
          if (targetIdx < 0 || targetIdx >= phases.length) return a;
          [phases[idx], phases[targetIdx]] = [phases[targetIdx], phases[idx]];
          return {
            ...a,
            phases: { ...a.phases, [category]: phases },
          };
        }),
      );
    },
    [],
  );

  return {
    commands,
    loadingCommands,
    commandsError,
    refreshCommands: loadCommands,
    actions,
    selectedActionId,
    selectAction,
    addAction,
    removeAction,
    updateAction,
    addPhase,
    removePhase,
    updatePhase,
    movePhase,
  };
}
