import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { JsonEditor, formatJsonEditorValue, validateJsonEditorText } from '../JsonEditor';

describe('Smart JsonEditor', () => {
  it('formats object values for editing', () => {
    expect(formatJsonEditorValue({ properties: [{ code: 'reflow_temp' }] })).toBe(
      '{\n  "properties": [\n    {\n      "code": "reflow_temp"\n    }\n  ]\n}',
    );
  });

  it('validates JSON object and array text only', () => {
    expect(validateJsonEditorText('{"yellow":50}')).toEqual({
      valid: true,
      parsed: { yellow: 50 },
    });
    expect(validateJsonEditorText('"scalar"')).toEqual({
      valid: false,
      reason: 'objectOrArray',
    });
    expect(validateJsonEditorText('{"yellow":')).toEqual({
      valid: false,
      reason: 'objectOrArray',
    });
  });

  it('shows invalid state while preserving the edited text in form state', () => {
    const onChange = vi.fn();
    render(
      <JsonEditor
        name="iot_p_schema_json"
        value='{"properties":[]}'
        onChange={onChange}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    const editor = screen.getByTestId('json-editor-iot_p_schema_json');
    fireEvent.change(editor, { target: { value: '{"properties":' } });

    expect(onChange).toHaveBeenLastCalledWith('{"properties":');
    expect(screen.getByTestId('json-editor-status-iot_p_schema_json')).toHaveTextContent('Invalid');
    expect(screen.getByText('JSON must be an object or array')).toBeInTheDocument();
  });

  it('formats the current valid JSON draft on demand', () => {
    const onChange = vi.fn();
    render(
      <JsonEditor
        name="iot_dp_alarm_thresholds"
        value='{"yellow":50,"red":80}'
        onChange={onChange}
        context={{ locale: 'zh-CN', t: (key: string) => key } as any}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Format' }));

    expect(onChange).toHaveBeenLastCalledWith('{\n  "yellow": 50,\n  "red": 80\n}');
  });
});
