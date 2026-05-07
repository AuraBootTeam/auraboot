import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithShell } from '../../../__tests__/test-utils';
import { EnvelopeRouter } from '../EnvelopeRouter';
import type { Envelope } from '../../../types/envelope';

function renderEnvelope(envelope: Envelope, props: Record<string, unknown> = {}) {
  return renderWithShell(<EnvelopeRouter envelope={envelope} {...props} />);
}

describe('EnvelopeRouter', () => {
  it('TextEnvelope renders text content', () => {
    renderEnvelope({ kind: 'text', text: 'hello world' });
    expect(screen.getByText('hello world')).toBeInTheDocument();
    expect(
      document.querySelector('[data-aurabot-envelope="text"]'),
    ).not.toBeNull();
  });

  it('ThinkingEnvelope shows label and toggles details', () => {
    renderEnvelope({ kind: 'thinking', text: 'pondering', tokens: 12 });
    const trigger = screen.getByRole('button');
    expect(trigger).toBeInTheDocument();
    expect(screen.queryByText('pondering')).not.toBeInTheDocument();
    fireEvent.click(trigger);
    expect(screen.getByText('pondering')).toBeInTheDocument();
  });

  it('PreviewEnvelope renders payload as JSON and surfaces risk level', () => {
    renderEnvelope({
      kind: 'preview',
      preview: { name: 'order' },
      riskLevel: 'MEDIUM',
    });
    const node = document.querySelector('[data-aurabot-envelope="preview"]');
    expect(node).not.toBeNull();
    expect(node!.textContent).toContain('"name": "order"');
    expect(
      document.querySelector('[data-aurabot-risk="MEDIUM"]'),
    ).not.toBeNull();
  });

  it('ResultEnvelope renders payload JSON', () => {
    renderEnvelope({ kind: 'result', payload: { id: 1 } });
    const node = document.querySelector('[data-aurabot-envelope="result"]');
    expect(node!.textContent).toContain('"id": 1');
  });

  it('ConfirmEnvelope without requireTextConfirm enables commit immediately', () => {
    const onConfirm = vi.fn();
    renderEnvelope(
      {
        kind: 'confirm',
        previewToken: 'px_1',
        riskLevel: 'MEDIUM',
        requireTextConfirm: null,
      },
      { onConfirm },
    );
    const commit = document.querySelector(
      '[data-aurabot-confirm-commit]',
    ) as HTMLButtonElement;
    expect(commit).not.toBeNull();
    expect(commit.disabled).toBe(false);
    fireEvent.click(commit);
    expect(onConfirm).toHaveBeenCalledWith('px_1');
  });

  it('ConfirmEnvelope with requireTextConfirm gates commit on exact match', () => {
    const onConfirm = vi.fn();
    renderEnvelope(
      {
        kind: 'confirm',
        previewToken: 'px_2',
        riskLevel: 'CRITICAL',
        requireTextConfirm: 'DELETE',
      },
      { onConfirm },
    );
    const commit = document.querySelector(
      '[data-aurabot-confirm-commit]',
    ) as HTMLButtonElement;
    expect(commit.disabled).toBe(true);

    const input = document.querySelector(
      '[data-aurabot-confirm-input]',
    ) as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'wrong' } });
    expect(commit.disabled).toBe(true);

    fireEvent.change(input, { target: { value: 'DELETE' } });
    expect(commit.disabled).toBe(false);
    fireEvent.click(commit);
    expect(onConfirm).toHaveBeenCalledWith('px_2');
  });

  it('SuggestionEnvelope renders chips and forwards click', () => {
    const onPick = vi.fn();
    renderEnvelope(
      {
        kind: 'suggestion',
        suggestions: [
          { label: 'Add field', skillName: 'field:add', paramsHint: {} },
          { label: 'Create page', skillName: 'page:create', paramsHint: {} },
        ],
      },
      { onSuggestionPick: onPick },
    );
    const chips = document.querySelectorAll('[data-aurabot-suggestion]');
    expect(chips).toHaveLength(2);
    fireEvent.click(chips[0]);
    expect(onPick).toHaveBeenCalledWith({
      label: 'Add field',
      skillName: 'field:add',
      paramsHint: {},
    });
  });

  it('WizardProgressEnvelope renders step ratio and progress bar', () => {
    renderEnvelope({
      kind: 'wizard-progress',
      step: 3,
      total: 8,
      label: 'Build list page',
    });
    const node = document.querySelector('[data-aurabot-envelope="wizard-progress"]');
    expect(node).not.toBeNull();
    expect(node!.textContent).toContain('3');
    expect(node!.textContent).toContain('8');
    expect(node!.textContent).toContain('Build list page');
    const bar = document.querySelector('[role="progressbar"]') as HTMLElement;
    expect(bar.getAttribute('aria-valuenow')).toBe('3');
    expect(bar.getAttribute('aria-valuemax')).toBe('8');
  });

  it('ErrorEnvelope shows code+message and retry button when provided', () => {
    const retry = vi.fn();
    renderEnvelope({
      kind: 'error',
      code: 'CONFIRM_REQUIRED',
      message: 'Need confirmation',
      retry,
    });
    expect(screen.getByText('Need confirmation')).toBeInTheDocument();
    expect(screen.getByText(/CONFIRM_REQUIRED/)).toBeInTheDocument();
    const button = document.querySelector(
      '[data-aurabot-error-retry]',
    ) as HTMLButtonElement;
    fireEvent.click(button);
    expect(retry).toHaveBeenCalled();
  });

  it('CodeEnvelope renders language label and code body', () => {
    renderEnvelope({ kind: 'code', language: 'json', code: '{"a":1}' });
    const node = document.querySelector('[data-aurabot-envelope="code"]');
    expect(node).not.toBeNull();
    expect(node!.getAttribute('data-aurabot-code-language')).toBe('json');
    expect(node!.textContent).toContain('{"a":1}');
  });
});
