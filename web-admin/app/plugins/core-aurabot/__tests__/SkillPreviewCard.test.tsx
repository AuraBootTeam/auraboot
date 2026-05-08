/**
 * SkillPreviewCard.test.tsx
 *
 * C-5 T7: pins the visible affordances of the skill confirmation UI:
 *   1. Renders skillName + risk badge + pretty-printed preview JSON.
 *   2. Confirm button invokes the provided callback with the toolId.
 *   3. Cancel button invokes the provided callback with the toolId.
 *   4. CRITICAL skills require typing the skillName before confirm enables.
 *
 * Mirrors the harness used by ThinkingBlock.test.tsx — no I18nProvider, so
 * useI18n() returns the supplied fallback strings directly.
 */

import React from 'react';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { SkillPreviewCard } from '../components-shell/SkillPreviewCard';

afterEach(() => {
  document.body.innerHTML = '';
});

const BASE_PROPS = {
  turnId: 'turn-abc',
  toolId: 'tool-001',
  skillName: 'demo:greet',
  preview: { greeting: 'hello', target: 'world' },
  previewToken: 'pt-xyz',
};

describe('SkillPreviewCard', () => {
  it('rendersSkillNameRiskBadgeAndPreviewJson — LOW tier', () => {
    render(
      <SkillPreviewCard
        {...BASE_PROPS}
        riskLevel="LOW"
        onConfirm={() => {}}
        onCancel={() => {}}
      />,
    );
    expect(screen.getByTestId('skill-preview-card')).toBeInTheDocument();
    expect(screen.getByTestId('skill-name').textContent).toBe('demo:greet');

    const badge = screen.getByTestId('risk-badge');
    expect(badge).toHaveAttribute('data-risk', 'LOW');
    expect(badge.textContent).toContain('LOW');

    const json = screen.getByTestId('preview-json');
    // Pretty-printed JSON contains both keys
    expect(json.textContent).toContain('"greeting": "hello"');
    expect(json.textContent).toContain('"target": "world"');
  });

  it('confirmButton_invokesOnConfirmWithToolId', () => {
    const onConfirm = vi.fn();
    render(
      <SkillPreviewCard
        {...BASE_PROPS}
        riskLevel="LOW"
        onConfirm={onConfirm}
        onCancel={() => {}}
      />,
    );
    fireEvent.click(screen.getByTestId('confirm-btn'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('tool-001');
  });

  it('cancelButton_invokesOnCancelWithToolId', () => {
    const onCancel = vi.fn();
    render(
      <SkillPreviewCard
        {...BASE_PROPS}
        riskLevel="MEDIUM"
        onConfirm={() => {}}
        onCancel={onCancel}
      />,
    );
    fireEvent.click(screen.getByTestId('cancel-btn'));
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onCancel).toHaveBeenCalledWith('tool-001');
  });

  it('criticalSkill_confirmDisabledUntilSkillNameTyped', () => {
    const onConfirm = vi.fn();
    render(
      <SkillPreviewCard
        {...BASE_PROPS}
        skillName="dangerous:drop_table"
        riskLevel="CRITICAL"
        onConfirm={onConfirm}
        onCancel={() => {}}
      />,
    );

    const confirmBtn = screen.getByTestId('confirm-btn') as HTMLButtonElement;
    const input = screen.getByTestId('critical-confirm-input') as HTMLInputElement;

    // Initially disabled — clicking is a no-op.
    expect(confirmBtn.disabled).toBe(true);
    fireEvent.click(confirmBtn);
    expect(onConfirm).not.toHaveBeenCalled();

    // Wrong text — still disabled.
    fireEvent.change(input, { target: { value: 'something_else' } });
    expect(confirmBtn.disabled).toBe(true);

    // Correct skillName — confirm enables and click fires the callback.
    fireEvent.change(input, { target: { value: 'dangerous:drop_table' } });
    expect(confirmBtn.disabled).toBe(false);
    fireEvent.click(confirmBtn);
    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith('tool-001');

    // Risk badge surfaces CRITICAL tier.
    expect(screen.getByTestId('risk-badge')).toHaveAttribute('data-risk', 'CRITICAL');
  });
});
