import { describe, it, expect } from 'vitest';
import { actionNodes } from '~/framework/smart/automation/nodes/actions';

/**
 * E.2 — Workflow LLM action vision config schema.
 *
 * Asserts that:
 *  - the action-llm-call node exposes an imageVariableNames property
 *  - the property is a JSON-typed field (List<String> on the wire)
 *  - the default config sets imageVariableNames to []
 *  - persisting a node config that supplies multiple image variable names
 *    survives a JSON round-trip (the shape backend executor expects:
 *    config.imageVariableNames: string[]).
 */
describe('action-llm-call: vision config (E.2)', () => {
  const node = actionNodes.find((n) => n.type === 'action-llm-call');

  it('node definition exists', () => {
    expect(node).toBeDefined();
  });

  it('configSchema declares imageVariableNames as a json field', () => {
    const field = node!.configSchema!.find((f) => f.key === 'imageVariableNames');
    expect(field).toBeDefined();
    expect(field!.type).toBe('json');
    expect(field!.group).toBe('prompt');
  });

  it('defaultConfig.imageVariableNames is an empty array', () => {
    expect(node!.defaultConfig?.imageVariableNames).toEqual([]);
  });

  it('persists 2 image variable names through a designer save round-trip', () => {
    // Simulate: designer renders the node with default config, user edits
    // imageVariableNames to ["screenshot", "attachment"], then the node is
    // serialized & rehydrated. Backend LlmCallExecutor reads the resulting
    // config.imageVariableNames as List<String>, so the contract this test
    // pins is "the persisted JSON literally contains those two strings, in
    // that order, under that key".
    const persisted = {
      ...node!.defaultConfig,
      imageVariableNames: ['screenshot', 'attachment'],
    };

    const roundTripped = JSON.parse(JSON.stringify(persisted));
    expect(roundTripped.imageVariableNames).toEqual(['screenshot', 'attachment']);
    expect(roundTripped.actionType).toBe('llm_call');
    // Existing fields untouched — regression guard.
    expect(roundTripped.model).toBe('claude-sonnet-4-6');
    expect(roundTripped.outputVariableName).toBe('llmOutput');
  });

  it('imageVariableNames precedes outputVariableName in panel order is irrelevant; both present', () => {
    // We don't pin field order (designer can reorder), but we DO pin presence
    // — losing either field silently would break workflows.
    const keys = node!.configSchema!.map((f) => f.key);
    expect(keys).toContain('imageVariableNames');
    expect(keys).toContain('outputVariableName');
    expect(keys).toContain('userPromptTemplate');
  });
});
