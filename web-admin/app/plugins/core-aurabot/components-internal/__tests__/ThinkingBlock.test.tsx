/**
 * ThinkingBlock.test.tsx
 *
 * Pins the collapse-by-default behaviour and the token-count header for the
 * Anthropic Extended Thinking renderer. Mirrors the existing vitest harness
 * used by ResultContractView.test.tsx.
 */

import React from 'react';
import { describe, it, expect, afterEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';

import { ThinkingBlock } from '../ThinkingBlock';

afterEach(() => {
  document.body.innerHTML = '';
});

const SAMPLE_CONTENT =
  'Step 1: parse the request. Step 2: choose the appropriate tool. Step 3: format the answer.';

describe('ThinkingBlock', () => {
  it('rendersCollapsedByDefault — header visible, content hidden', () => {
    render(<ThinkingBlock content={SAMPLE_CONTENT} tokens={42} />);
    expect(screen.getByTestId('thinking-block')).toBeInTheDocument();
    expect(screen.getByTestId('thinking-block-toggle')).toBeInTheDocument();
    // Body must not be present when collapsed
    expect(screen.queryByTestId('thinking-block-content')).not.toBeInTheDocument();
    // aria-expanded reflects collapse state
    expect(screen.getByTestId('thinking-block-toggle')).toHaveAttribute('aria-expanded', 'false');
  });

  it('expandsOnClick — body becomes visible after toggling', () => {
    render(<ThinkingBlock content={SAMPLE_CONTENT} tokens={42} />);
    const toggle = screen.getByTestId('thinking-block-toggle');

    fireEvent.click(toggle);

    expect(screen.getByTestId('thinking-block-content')).toBeInTheDocument();
    expect(screen.getByTestId('thinking-block-content').textContent).toContain('Step 1');
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    // Click again to collapse
    fireEvent.click(toggle);
    expect(screen.queryByTestId('thinking-block-content')).not.toBeInTheDocument();
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('displaysTokenCountInHeader — explicit tokens prop wins over estimate', () => {
    render(<ThinkingBlock content={SAMPLE_CONTENT} tokens={1234} />);
    expect(screen.getByTestId('thinking-block-toggle').textContent).toContain('1234 tokens');
  });

  it('falls back to a rough estimate when tokens prop is omitted', () => {
    const tenWords = 'word word word word word word word word word word';
    render(<ThinkingBlock content={tenWords} />);
    // 10 words × 1.3 ≈ 13
    expect(screen.getByTestId('thinking-block-toggle').textContent).toMatch(/13 tokens/);
  });

  it('honours initiallyCollapsed=false to render expanded on first paint', () => {
    render(<ThinkingBlock content={SAMPLE_CONTENT} tokens={5} initiallyCollapsed={false} />);
    expect(screen.getByTestId('thinking-block-content')).toBeInTheDocument();
    expect(screen.getByTestId('thinking-block-toggle')).toHaveAttribute('aria-expanded', 'true');
  });

  // M12: i18n compliance — without an I18nProvider wrapper, the default
  // useI18n() returns the supplied fallback string instead of a key. The
  // component must (a) render the localised "推理过程" label, not a raw
  // i18n key like "aurabot.thinking.label", and (b) interpolate the token
  // count into the {count} placeholder so the label stays meaningful.
  it('rendersLocalisedLabel — falls back to zh-CN copy when no I18nProvider', () => {
    render(<ThinkingBlock content={SAMPLE_CONTENT} tokens={42} />);
    const toggleText = screen.getByTestId('thinking-block-toggle').textContent ?? '';
    expect(toggleText).toContain('推理过程');
    // Must NOT leak the i18n key
    expect(toggleText).not.toContain('aurabot.thinking');
    // Token count interpolated
    expect(toggleText).toContain('42 tokens');
  });

  // Minor 15: ensure the body uses a sans-serif font (natural-language
  // prose), not the previous font-mono.
  it('rendersThinkingProseInSansFont — body uses font-sans not font-mono', () => {
    render(<ThinkingBlock content={SAMPLE_CONTENT} initiallyCollapsed={false} />);
    const body = screen.getByTestId('thinking-block-content').firstChild as HTMLElement | null;
    expect(body).not.toBeNull();
    expect(body!.className).toContain('font-sans');
    expect(body!.className).not.toContain('font-mono');
  });
});
