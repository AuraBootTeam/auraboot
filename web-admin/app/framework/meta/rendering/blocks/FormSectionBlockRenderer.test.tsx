/**
 * FormSectionBlockRenderer.test.tsx
 *
 * 块级 readOnly 支持（跨机 CI 视觉验收 BR-009 发现的回归面）：
 *  1. block.readOnly = true 时，块内每个字段都以只读形态渲染（输入不可编辑）。
 *  2. block.readOnly 未声明时，字段保持原有可编辑渲染。
 *  3. 只读块的标题与字段标签仍然渲染（不因只读而丢失语义信息）。
 */

import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { FormSectionBlockRenderer } from './FormSectionBlockRenderer';
import type { BlockConfig, FieldConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

vi.mock('~/framework/meta/rendering/FieldRenderer', () => ({
  FieldRenderer: ({ field }: { field: { field: string; readOnly?: boolean } }) => (
    <input
      aria-label={field.field}
      data-testid={`field-${field.field}`}
      readOnly={Boolean(field.readOnly)}
      value=""
      onChange={() => {}}
    />
  ),
}));

vi.mock('~/routes/_shared/dynamic-route-utils', () => ({
  getLocalizedText: (value: any) =>
    typeof value === 'string' ? value : (value?.['zh-CN'] ?? value?.en ?? ''),
}));

function makeRuntime(): SchemaRuntime {
  return {
    getContext: () => ({ locale: 'zh-CN', t: (k: string) => k }),
    getStateManager: () => ({
      getFieldValue: () => '',
    }),
  } as unknown as SchemaRuntime;
}

function makeBlock(readOnly?: boolean): BlockConfig {
  const fields: FieldConfig[] = [
    { field: 'qtr_ii_snapshot_ref' },
    { field: 'qtr_ii_trace_unit_ref' },
    { field: 'qtr_ii_evidence_ref' },
  ] as unknown as FieldConfig[];
  return {
    id: 'immutable_notice',
    blockType: 'detail-section',
    title: { 'zh-CN': '系统由影响快照生成，只读且不可手工保存' },
    fields,
    ...(readOnly !== undefined ? { readOnly } : {}),
  } as unknown as BlockConfig;
}

describe('FormSectionBlockRenderer — block-level readOnly', () => {
  it('renders every field read-only when block.readOnly is true', () => {
    render(<FormSectionBlockRenderer block={makeBlock(true)} runtime={makeRuntime()} />);
    for (const f of ['qtr_ii_snapshot_ref', 'qtr_ii_trace_unit_ref', 'qtr_ii_evidence_ref']) {
      expect(screen.getByTestId(`field-${f}`)).toHaveAttribute('readonly', '');
    }
  });

  it('keeps fields editable when block.readOnly is not declared', () => {
    render(<FormSectionBlockRenderer block={makeBlock(undefined)} runtime={makeRuntime()} />);
    for (const f of ['qtr_ii_snapshot_ref', 'qtr_ii_trace_unit_ref', 'qtr_ii_evidence_ref']) {
      expect(screen.getByTestId(`field-${f}`)).not.toHaveAttribute('readonly');
    }
  });

  it('still renders the block title for a read-only block', () => {
    render(<FormSectionBlockRenderer block={makeBlock(true)} runtime={makeRuntime()} />);
    expect(screen.getByText('系统由影响快照生成，只读且不可手工保存')).toBeInTheDocument();
  });
});
