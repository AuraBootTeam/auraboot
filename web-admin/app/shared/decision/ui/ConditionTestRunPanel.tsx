import { useState } from 'react';
import {
  type ConditionNode, type ScopedContext, type Truth, type PathOperand,
  evaluatePreview, toNaturalLanguage,
} from '../ast/conditionAst';

/**
 * DecisionOps client-side test-run preview (mockup "测试运行" step, docs/1.md §17.2): evaluate a
 * Condition AST against named sample contexts and show the three-valued result (TRUE/FALSE/UNKNOWN).
 * Preview only — the backend test-run/evaluate remains authoritative (front-end never decides).
 */

export interface TestSample { label: string; context: ScopedContext }

export interface ConditionTestRunPanelProps {
  condition: ConditionNode;
  samples: TestSample[];
  labelOf?: (o: PathOperand) => string;
}

const TRUTH_LABEL: Record<Truth, string> = { TRUE: '命中', FALSE: '未命中', UNKNOWN: '未知' };
const TRUTH_CLASS: Record<Truth, string> = { TRUE: 'truth-true', FALSE: 'truth-false', UNKNOWN: 'truth-unknown' };

export function ConditionTestRunPanel({ condition, samples, labelOf }: ConditionTestRunPanelProps) {
  const [selected, setSelected] = useState(0);
  const sample = samples[selected];
  const result: Truth | null = sample ? evaluatePreview(condition, sample.context) : null;

  return (
    <div data-testid="condition-testrun">
      <div className="trp-samples">
        {samples.map((s, i) => (
          <button
            type="button"
            key={s.label}
            data-testid={`sample-${i}`}
            aria-pressed={i === selected}
            onClick={() => setSelected(i)}
          >{s.label}</button>
        ))}
      </div>

      <div className="trp-nl" data-testid="trp-nl">{toNaturalLanguage(condition, labelOf)}</div>

      {result && (
        <div
          data-testid="trp-result"
          data-truth={result}
          className={`trp-result ${TRUTH_CLASS[result]}`}
        >{TRUTH_LABEL[result]}</div>
      )}
      <div data-testid="trp-note" className="trp-note">预览仅辅助,以后端 test-run 为准</div>
    </div>
  );
}

export default ConditionTestRunPanel;
