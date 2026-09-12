import { describe, it, expect } from 'vitest';
import { automationNodes, triggerNodes } from '~/framework/smart/automation/nodes';

/**
 * trigger-workflow-event node definition tests.
 *
 * Asserts that:
 *  - automationNodes and triggerNodes contain the new node
 *  - defaultConfig.triggerType === 'on_workflow_event'
 *  - configSchema has a required 'modelCode' field of type 'workflow-select'
 *  - configSchema has an 'eventTypes' field of type 'multiselect' with all 5 workflow event options
 */
describe('trigger-workflow-event node definition', () => {
  const nodeFromAutomationNodes = automationNodes.find((n) => n.type === 'trigger-workflow-event');
  const nodeFromTriggerNodes = triggerNodes.find((n) => n.type === 'trigger-workflow-event');

  it('automationNodes contains trigger-workflow-event', () => {
    expect(nodeFromAutomationNodes).toBeDefined();
  });

  it('triggerNodes contains trigger-workflow-event', () => {
    expect(nodeFromTriggerNodes).toBeDefined();
  });

  it('defaultConfig.triggerType is on_workflow_event', () => {
    expect(nodeFromTriggerNodes!.defaultConfig?.triggerType).toBe('on_workflow_event');
  });

  it('configSchema has required modelCode field of type workflow-select', () => {
    const field = nodeFromTriggerNodes!.configSchema!.find((f) => f.key === 'modelCode');
    expect(field).toBeDefined();
    expect(field!.type).toBe('workflow-select');
    expect(field!.required).toBe(true);
    expect(field!.group).toBe('trigger_source');
  });

  it('configSchema has eventTypes field of type multiselect', () => {
    const field = nodeFromTriggerNodes!.configSchema!.find((f) => f.key === 'eventTypes');
    expect(field).toBeDefined();
    expect(field!.type).toBe('multiselect');
  });

  it('eventTypes field has exactly 5 options with correct values', () => {
    const field = nodeFromTriggerNodes!.configSchema!.find((f) => f.key === 'eventTypes');
    const values = field!.options!.map((o) => o.value);
    expect(values).toHaveLength(5);
    expect(values).toContain('process_started');
    expect(values).toContain('process_ended');
    expect(values).toContain('task_created');
    expect(values).toContain('task_completed');
    expect(values).toContain('task_assigned');
  });

  it('eventTypes options all use i18n labels (no hardcoded strings)', () => {
    const field = nodeFromTriggerNodes!.configSchema!.find((f) => f.key === 'eventTypes');
    for (const option of field!.options!) {
      expect(typeof option.label).toBe('string');
      expect(option.label).toMatch(/^\$i18n:/);
    }
  });

  it('node category is trigger', () => {
    expect(nodeFromTriggerNodes!.category).toBe('trigger');
  });

  it('node has a non-empty icon', () => {
    expect(nodeFromTriggerNodes!.icon).toBeTruthy();
  });

  it('node label and description use i18n keys', () => {
    expect(nodeFromTriggerNodes!.label).toMatch(/^\$i18n:/);
    expect(nodeFromTriggerNodes!.description).toMatch(/^\$i18n:/);
  });
});
