import { describe, expect, it } from 'vitest';
import { ALL_COMPONENT_CONFIGS } from '../ComponentConfigs';

describe('ComponentConfigs', () => {
  it('exposes decision rule binding as a platform-discoverable DSL component', () => {
    const config = ALL_COMPONENT_CONFIGS.find((component) => component.type === 'decisionrulebinding');

    expect(config).toBeDefined();
    expect(config).toMatchObject({
      name: 'Decision Rule Binding',
      category: 'display',
      runtime: expect.objectContaining({
        componentName: 'DecisionRuleBindingBlock',
      }),
    });
    expect(config?.tags).toEqual(
      expect.arrayContaining(['rule-center', 'condition', 'decision-binding']),
    );
    expect(config?.propertySchema.map((property) => property.key)).toEqual(
      expect.arrayContaining([
        'mode',
        'valueField',
        'consumerType',
        'consumerCode',
        'consumerNodeId',
        'initialDecisionCode',
        'initialVersionPolicy',
        'showImpactPreview',
        'showTestRunner',
        'initialContextJson',
        'fields',
        'decisions',
      ]),
    );
  });
});
