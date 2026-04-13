import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DesignerPreview } from '~/plugins/core-designer/components/studio/workbench/runtime/DesignerPreview';
import type { FormSchema } from '~/plugins/core-designer/components/studio/domain/schema/types';

vi.mock('~/meta/rendering/SchemaRenderer', () => ({
  SchemaRendererWithContainer: ({ runtime }: any) => (
    <div data-testid="schema-renderer">{runtime ? 'rendered' : 'empty'}</div>
  ),
}));

const mockConvert = vi.fn();
const mockUsePageDataSources = vi.fn();
const mockUseSchemaRuntime = vi.fn();

vi.mock('~/plugins/core-designer/components/studio/services/runtime/SchemaRuntimeAdapter', () => ({
  convertSchemaToUnified: (schema: any) => mockConvert(schema),
  usePageDataSources: (params: any) => mockUsePageDataSources(params),
  useSchemaRuntime: (params: any) => mockUseSchemaRuntime(params),
}));

const baseSchema: FormSchema = {
  id: 'form_1',
  kind: 'form',
  name: '测试表单',
  title: '测试表单',
  description: '',
  version: '1.0.0',
  components: [],
  layout: {
    type: 'grid',
    spacing: 16,
    padding: 24,
    columns: 4,
  },
  metadata: {
    createdAt: '',
    updatedAt: '',
    createdBy: 'tester',
    tags: [],
  },
};

describe('Studio DesignerPreview', () => {
  beforeEach(() => {
    mockConvert.mockReset();
    mockUsePageDataSources.mockReset();
    mockUseSchemaRuntime.mockReset();

    mockConvert.mockImplementation(() => ({
      kind: 'form',
      version: '1.0.0',
      id: 'form_1',
      layout: { type: 'stack' },
      blocks: [],
    }));
    mockUsePageDataSources.mockImplementation(() => ({ manager: {} }));
  });

  it('shows initialization message when runtime is not ready', () => {
    mockUseSchemaRuntime.mockReturnValue(null);

    render(<DesignerPreview schema={baseSchema} />);

    expect(screen.getByText('初始化预览运行时...')).toBeInTheDocument();
  });

  it('renders schema when runtime is available', () => {
    mockUseSchemaRuntime.mockReturnValue({ runtime: true });

    render(<DesignerPreview schema={baseSchema} />);

    expect(screen.getByTestId('schema-renderer')).toHaveTextContent('rendered');
  });
});
