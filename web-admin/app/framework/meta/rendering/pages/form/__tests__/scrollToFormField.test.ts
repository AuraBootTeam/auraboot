/**
 * scrollToFormField — on submit-validation failure the page jumps to + focuses
 * the first invalid field (standard §4 mixed-timing validation).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { scrollToFormField } from '~/framework/meta/rendering/pages/form/scrollToFormField';

describe('scrollToFormField', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('scrolls to and focuses the input inside the matching field wrapper', () => {
    document.body.innerHTML = `
      <div data-testid="form-field-name"><input id="name-input" /></div>
      <div data-testid="form-field-email"><input id="email-input" /></div>`;
    const wrapper = document.querySelector<HTMLElement>('[data-testid="form-field-name"]')!;
    const scrollSpy = vi.fn();
    wrapper.scrollIntoView = scrollSpy;

    const ok = scrollToFormField('name', document);

    expect(ok).toBe(true);
    expect(scrollSpy).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
    expect(document.activeElement?.id).toBe('name-input');
  });

  it('returns false when no field wrapper matches', () => {
    document.body.innerHTML = `<div data-testid="form-field-other"><input /></div>`;
    expect(scrollToFormField('missing', document)).toBe(false);
  });

  it('scrolls even when the wrapper has no focusable child', () => {
    document.body.innerHTML = `<div data-testid="form-field-readonly"><span>x</span></div>`;
    const wrapper = document.querySelector<HTMLElement>('[data-testid="form-field-readonly"]')!;
    const scrollSpy = vi.fn();
    wrapper.scrollIntoView = scrollSpy;
    expect(scrollToFormField('readonly', document)).toBe(true);
    expect(scrollSpy).toHaveBeenCalled();
  });

  it('focuses select / textarea too', () => {
    document.body.innerHTML = `<div data-testid="form-field-bio"><textarea id="bio"></textarea></div>`;
    document.querySelector<HTMLElement>('[data-testid="form-field-bio"]')!.scrollIntoView = vi.fn();
    scrollToFormField('bio', document);
    expect(document.activeElement?.id).toBe('bio');
  });
});
