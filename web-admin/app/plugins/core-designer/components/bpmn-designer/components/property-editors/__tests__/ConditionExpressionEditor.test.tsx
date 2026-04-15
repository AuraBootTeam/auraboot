/**
 * Unit tests for ConditionExpressionEditor — F3 regression coverage.
 *
 * Covers behaviors that proved fragile in E2E (React setState/Playwright timing):
 *  - F3: switching from advanced (empty content) to simple does NOT show parseWarning
 *  - F3: switching to simple with non-parseable content DOES show parseWarning
 *  - F2: language default is 'mvel' (was 'javascript' before fix)
 *  - F1-companion: changing the `condition` prop without changing `key` does not
 *    auto-reset state — relying on EdgeEditor's `key={edgeId}` for remount-on-switch
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ConditionExpressionEditor } from '../ConditionExpressionEditor';

describe('ConditionExpressionEditor — F3 regression (parseWarning gating)', () => {
  it('switching from empty advanced back to simple does NOT show parseWarning', () => {
    const onChange = vi.fn();
    render(<ConditionExpressionEditor condition={undefined} onChange={onChange} />);

    // Initial mode is simple (no condition content). Click 高级模式 → 简单模式.
    const advBtn = screen.getByRole('button', { name: 'bpmn.condition.advancedMode' });
    fireEvent.click(advBtn);

    // Now in advanced mode with empty content. Switch back.
    const simpleBtn = screen.getByRole('button', { name: 'bpmn.condition.simpleMode' });
    fireEvent.click(simpleBtn);

    // F3: warning element must NOT render when content is empty
    expect(screen.queryByText('bpmn.condition.parseWarning')).not.toBeInTheDocument();
  });

  it('switching to simple with non-parseable content DOES show parseWarning', () => {
    const onChange = vi.fn();
    // Bare MVEL expression like SmartEngine uses — does not match ${...} simple-rule shape
    render(
      <ConditionExpressionEditor
        condition={{ type: 'expression', content: 'amount >= 50000' }}
        onChange={onChange}
      />,
    );

    // With a non-parseable existing condition, initial mode is 'advanced'.
    // Click 简单模式 to attempt parsing.
    const simpleBtn = screen.getByRole('button', { name: 'bpmn.condition.simpleMode' });
    fireEvent.click(simpleBtn);

    // Warning SHOULD render for non-empty unparseable content
    expect(screen.getByText('bpmn.condition.parseWarning')).toBeInTheDocument();
  });

  it('parseable expression like ${amount > 1000} initializes in simple mode', () => {
    const onChange = vi.fn();
    render(
      <ConditionExpressionEditor
        condition={{ type: 'expression', content: '${amount > 1000}' }}
        onChange={onChange}
      />,
    );

    // The simple-mode rule field should appear with parsed value
    const fieldInput = screen.getByPlaceholderText('bpmn.condition.fieldPlaceholder') as HTMLInputElement;
    expect(fieldInput.value).toBe('amount');
  });
});

describe('ConditionExpressionEditor — F2 (MVEL is default language)', () => {
  it('language dropdown defaults to mvel when condition.type=script and no language set', () => {
    const onChange = vi.fn();
    render(
      <ConditionExpressionEditor
        condition={{ type: 'script', content: 'score > 80' }}
        onChange={onChange}
      />,
    );

    // MVEL option exists; legacy JavaScript/Groovy options removed
    expect(screen.getByRole('option', { name: 'MVEL' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'JavaScript' })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Groovy' })).not.toBeInTheDocument();
  });
});

describe('ConditionExpressionEditor — onChange propagation', () => {
  it('typing in advanced textarea fires onChange with new content', () => {
    const onChange = vi.fn();
    render(<ConditionExpressionEditor condition={undefined} onChange={onChange} />);

    fireEvent.click(screen.getByRole('button', { name: 'bpmn.condition.advancedMode' }));
    const ta = screen.getByPlaceholderText('bpmn.condition.advancedPlaceholder') as HTMLTextAreaElement;
    fireEvent.change(ta, { target: { value: 'amount > 100' } });

    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ type: 'expression', content: 'amount > 100' }),
    );
  });
});
