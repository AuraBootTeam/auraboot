import { describe, expect, it } from 'vitest';
import { COMPONENT_RUNTIME_MANIFEST } from '../ComponentRuntimeManifest';

describe('QuoteOps runtime component manifest', () => {
  it('registers the process-fee rule matrix custom block component', () => {
    expect(COMPONENT_RUNTIME_MANIFEST.processfeerulematrix).toMatchObject({
      modulePath: '../../../../ui/smart/quoteops/ProcessFeeRuleMatrixBlock.tsx',
      exportName: 'ProcessFeeRuleMatrixBlock',
      componentName: 'ProcessFeeRuleMatrixBlock',
    });
    expect(COMPONENT_RUNTIME_MANIFEST.processfeerulematrix.aliases).toContain('process_fee_rule_matrix');
  });
});
