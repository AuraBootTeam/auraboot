import React from 'react';
import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { SemanticResolutionEvidence } from '../SemanticResolutionEvidence';

it('uses configured field labels and keeps technical details collapsed', () => {
  render(
    <SemanticResolutionEvidence
      traces={[
        {
          standardField: 'tolerance_fraction',
          state: 'conflict',
          selected: {
            normalizedValue: '',
            sourceRole: 'mixed',
            sourceColumn: '规格',
            ruleId: 'attributes.scoped-category-grammar',
            ruleVersion: '2',
          },
          alternatives: [{ normalizedValue: '0.01' }, { normalizedValue: '0.05' }],
        },
      ]}
      fieldLabels={{ tolerance_fraction: { 'zh-CN': '容差', en: 'Tolerance' } }}
      locale="zh-CN"
      t={(key) => key}
    />,
  );
  const evidence = screen.getByTestId('review-drawer-semantic-resolution-tolerance_fraction');
  expect(evidence).toHaveTextContent('容差');
  expect(evidence).toHaveTextContent('混合描述');
  expect(evidence.querySelector('details')).not.toHaveAttribute('open');
  expect(evidence.querySelector('details')).toHaveTextContent('attributes.scoped-category-grammar');
});
