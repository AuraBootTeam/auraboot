import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// D4 — inline field validation feedback in the inspector. Mock the http-client so
// the remote selectors (D2) don't make real calls; the test exercises only the
// validation rendering on text/number fields.
const { mockGet } = vi.hoisted(() => ({ mockGet: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get: mockGet }));
mockGet.mockResolvedValue({ data: { records: [] } });

import { SchemaInspector } from '../inspector/SchemaInspector';
import { defaultInspectorSchemaRegistry } from '../registry/InspectorSchemaRegistry';
import type { DslBlockV3 } from '../types';

describe('D4 inline field validation — schema metadata', () => {
  it('attaches number bounds to span and required to embedded-list modelCode', () => {
    const span = defaultInspectorSchemaRegistry
      .getFields('stat-card')
      .find((field) => field.key === 'layout.span');
    expect(span?.min).toBe(1);
    expect(span?.max).toBe(24);

    const modelCode = defaultInspectorSchemaRegistry
      .getFields('embedded-list')
      .find((field) => field.key === 'modelCode');
    expect(modelCode?.required).toBe(true);
  });
});

describe('D4 inline field validation — inspector rendering', () => {
  it('shows a min-bound error when span is below the allowed range', () => {
    const block = {
      id: 's1',
      blockType: 'stat-card',
      title: 'Card',
      layout: { span: 0 },
    } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    const error = screen.getByTestId('inspector-field-error-layout.span');
    expect(error).toBeInTheDocument();
    expect(error).toHaveTextContent(/≥ 1|不能小于 1/);
    expect(screen.getByTestId('inspector-field-layout.span')).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows a max-bound error when span exceeds the allowed range', () => {
    const block = {
      id: 's2',
      blockType: 'stat-card',
      title: 'Card',
      layout: { span: 99 },
    } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    expect(screen.getByTestId('inspector-field-error-layout.span')).toHaveTextContent(/≤ 24|不能大于 24/);
  });

  it('shows NO error for a valid span', () => {
    const block = {
      id: 's3',
      blockType: 'stat-card',
      title: 'Card',
      layout: { span: 12 },
    } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    expect(screen.queryByTestId('inspector-field-error-layout.span')).toBeNull();
    expect(screen.getByTestId('inspector-field-layout.span')).toHaveAttribute('aria-invalid', 'false');
  });

  it('flags a required field (embedded-list modelCode) when empty, with an asterisk', () => {
    const block = {
      id: 'e1',
      blockType: 'embedded-list',
      title: 'List',
    } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    const error = screen.getByTestId('inspector-field-error-modelCode');
    expect(error).toBeInTheDocument();
    expect(error).toHaveTextContent(/required|必填/);
  });

  it('clears the required error once the field has a value', () => {
    const block = {
      id: 'e2',
      blockType: 'embedded-list',
      title: 'List',
      modelCode: 'order_line',
    } as unknown as DslBlockV3;
    render(<SchemaInspector block={block} onChange={() => {}} />);

    expect(screen.queryByTestId('inspector-field-error-modelCode')).toBeNull();
  });
});
