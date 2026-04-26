import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { LocalizedTextInput } from '../LocalizedTextInput';

// BACKLOG-G2-001 (P2 design): the i18n toggle button must render even when
// the caller does not pass `label`, because BlockSettingsEditor wraps the
// input in <PropertyField label="..."> and relies on the outer label rather
// than passing one through. Earlier behavior gated the toggle on `label`
// being truthy, which silently dropped the multi-locale entry point for
// section titles.
describe('LocalizedTextInput', () => {
  it('renders the multi-language toggle without a label prop', () => {
    render(
      <LocalizedTextInput
        value=""
        onChange={() => {}}
        testId="title"
      />,
    );

    const toggle = screen.getByTestId('title-toggle');
    expect(toggle).toBeInTheDocument();
    expect(toggle).toHaveTextContent('多语言');
  });

  it('expands to show en-US input when toggle is clicked', () => {
    const onChange = vi.fn();
    render(
      <LocalizedTextInput
        value="hello"
        onChange={onChange}
        testId="title"
      />,
    );

    fireEvent.click(screen.getByTestId('title-toggle'));

    expect(screen.getByTestId('title-zh')).toHaveValue('hello');
    expect(screen.getByTestId('title-en')).toBeInTheDocument();
    // toggle now offers collapse
    expect(screen.getByTestId('title-toggle')).toHaveTextContent('折叠');
    // expanding emits an object-form value
    expect(onChange).toHaveBeenCalledWith({ 'zh-CN': 'hello' });
  });

  it('hides the toggle when value is an i18n key (pass-through mode)', () => {
    render(
      <LocalizedTextInput
        value="$i18n:section.title"
        onChange={() => {}}
        testId="title"
      />,
    );

    expect(screen.queryByTestId('title-toggle')).not.toBeInTheDocument();
  });
});
