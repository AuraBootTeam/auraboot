import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { evaluateCondition as evaluateExpressionCondition } from '~/framework/meta/runtime/expression/evaluator';
import {
  collectSemanticResolutionTraces,
  ReviewDrawerBlockRenderer,
} from '../ReviewDrawerBlockRenderer';

function runtime(selectedLine: Record<string, unknown>, rawItems: any[]): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'zh-CN',
    t: (key: string) => key,
    state: { selectedLine },
    form: {},
    global: {},
  };
  return {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: (expression: string, value: any = context) =>
        evaluateExpressionCondition(expression, value),
      evaluateTemplate: (template: string) => template,
      evaluateObject: (value: any) => value,
    }),
    getDataSourceManager: () => ({
      getData: (id: string) => (id === 'rawItems' ? rawItems : []),
      has: () => false,
      register: vi.fn(),
      reload: vi.fn().mockResolvedValue(undefined),
    }),
    getStateManager: () => ({ updateState: vi.fn(), getContext: () => context }),
    getScopeId: () => 'scope-semantic',
    getSchema: () => ({ id: 'bom_workbench', modelCode: 'bom_standard_line_pcba' }),
  } as unknown as SchemaRuntime;
}

const resolutionGroups = [
  {
    sourceField: 'bom_raw_extra_columns_json',
    path: '__parse_evidence.parsePlan.fieldEvidence',
    itemPath: 'semanticResolution',
  },
  {
    sourceField: 'bom_raw_extra_columns_json',
    path: '__parse_evidence.semanticFieldResolutions',
  },
];

const evidence = {
  __parse_evidence: {
    parsePlan: {
      fieldEvidence: [
        {
          semanticResolution: {
            standardField: 'mpn',
            state: 'conflict',
            selected: {},
            alternatives: [
              {
                normalizedValue: 'ABC-123',
                sourceColumn: 'MPN',
                sourceRole: 'dedicated',
                sourceSpan: { startOffset: 0, endOffset: 7 },
                ruleId: 'mpn.part-number-field-token',
                ruleVersion: 'bom-token-grammar-v11',
              },
              { normalizedValue: 'XYZ-456' },
            ],
            vetoes: ['MPN_SOURCE_CONFLICT'],
          },
        },
      ],
    },
    semanticFieldResolutions: [
      {
        standardField: 'capacitance',
        state: 'resolved',
        selected: {
          normalizedValue: '100nF',
          sourceColumn: 'row-envelope',
          sourceRole: 'mixed',
          sourceSpan: { startOffset: 0, endOffset: 29 },
          ruleId: 'attributes.scoped-category-grammar',
          ruleVersion: '1',
        },
        alternatives: [],
        vetoes: [],
      },
    ],
  },
};

describe('ReviewDrawerBlockRenderer semantic resolution trace', () => {
  it('collects generic ParsePlan and category-attribute traces through declarative paths', () => {
    expect(
      collectSemanticResolutionTraces(
        { bom_raw_extra_columns_json: { type: 'jsonb', value: evidence } },
        resolutionGroups,
      ).map((item) => item.standardField),
    ).toEqual(['mpn', 'capacitance']);
  });

  it('accepts resolution group config serialized by the dynamic page runtime', () => {
    expect(
      collectSemanticResolutionTraces(
        { bom_raw_extra_columns_json: JSON.stringify(evidence) },
        JSON.stringify(resolutionGroups),
      ).map((item) => item.standardField),
    ).toEqual(['mpn', 'capacitance']);
  });

  it('shows selected value, alternatives, source span, rule version and vetoes only in evidence details', () => {
    const block = {
      id: 'bom_review',
      blockType: 'review-drawer',
      context: '${state.selectedLine}',
      contextKeyField: 'pid',
      source: {
        record: {
          dataSource: 'rawItems',
          matchField: 'bom_raw_row_no',
          recordField: 'bom_std_raw_row_no',
        },
        resolutionGroups,
        openByDefault: false,
      },
    } as unknown as BlockConfig;
    render(
      <ReviewDrawerBlockRenderer
        block={block}
        runtime={runtime({ pid: 'std-1', bom_std_raw_row_no: 4 }, [
          { pid: 'raw-1', bom_raw_row_no: 4, bom_raw_extra_columns_json: JSON.stringify(evidence) },
        ])}
      />,
    );

    expect(screen.getByTestId('review-drawer-semantic-resolutions')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-semantic-resolution-mpn')).toHaveTextContent(
      'ABC-123, XYZ-456',
    );
    expect(screen.getByTestId('review-drawer-semantic-resolution-mpn')).toHaveTextContent(
      'MPN_SOURCE_CONFLICT',
    );
    expect(screen.getByTestId('review-drawer-semantic-resolution-capacitance')).toHaveTextContent(
      '100nF',
    );
    expect(screen.getByTestId('review-drawer-semantic-resolution-capacitance')).toHaveTextContent(
      'row-envelope · mixed [0, 29)',
    );
    expect(screen.getByTestId('review-drawer-semantic-resolution-capacitance')).toHaveTextContent(
      'attributes.scoped-category-grammar @ 1',
    );
    expect(screen.getByTestId('review-drawer-tab-source')).not.toHaveAttribute('open');
  });
});
