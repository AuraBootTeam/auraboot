import { describe, expect, it } from 'vitest';
import { formatConditionForDisplay } from '~/plugins/core-designer/components/bpmn-designer/components/edges/ConditionalEdge';

describe('formatConditionForDisplay', () => {
  it('hides trivial always-true conditions', () => {
    expect(formatConditionForDisplay('${true}')).toBeNull();
    expect(formatConditionForDisplay(' true ')).toBeNull();
  });

  it('keeps business conditions visible', () => {
    expect(formatConditionForDisplay("${taskResult == 'approved'}")).toBe(
      "${taskResult == 'approved'}",
    );
  });
});
