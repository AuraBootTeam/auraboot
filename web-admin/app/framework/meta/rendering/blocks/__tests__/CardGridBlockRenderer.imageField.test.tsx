import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

vi.mock('~/framework/meta/hooks/useActionHandler', () => ({
  useActionHandler: () => ({
    handleAction: vi.fn(),
    loading: false,
    error: null,
    setError: vi.fn(),
  }),
}));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ token: 'token', hasPermission: () => true }),
}));
vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
    showWarningToast: vi.fn(),
    showInfoToast: vi.fn(),
  }),
}));
vi.mock('react-router', () => ({ useNavigate: () => vi.fn() }));

import { CardGridBlockRenderer } from '../CardGridBlockRenderer';

function runtime(rows: Record<string, unknown>[]): SchemaRuntime {
  const context: Record<string, any> = { locale: 'zh-CN', t: (key: string) => key, state: {} };
  return {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (value: any) => value,
      evaluateObject: (value: any) => value,
    }),
    getDataSourceManager: () => ({
      getData: () => rows,
      getState: () => ({ data: rows, loading: false, error: null }),
      subscribe: () => vi.fn(),
      reload: vi.fn(),
    }),
    getStateManager: () => ({
      updateState: (_scope: string, key: string, value: unknown) => {
        context.state[key] = value;
      },
      getContext: () => context,
    }),
    getScopeId: () => 'card-grid-test-scope',
    getSchema: () => ({ id: 'species', modelCode: 'xy_pet_species' }),
  } as unknown as SchemaRuntime;
}

describe('CardGridBlockRenderer imageField', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  const block = {
    id: 'grid',
    blockType: 'card-grid',
    dataSource: 'rows',
    titleField: 'name',
    imageField: 'cover',
  };

  it('renders an image tile when the row carries an image URL', () => {
    render(
      <CardGridBlockRenderer
        block={block as any}
        runtime={runtime([{ pid: '1', name: '芽芽猫', cover: '/static/xiaoya/cat.png' }])}
      />,
    );
    expect(screen.getAllByTestId('card-grid-image').length).toBe(1);
    const img = screen.getByRole('img', { name: '芽芽猫' });
    expect(img.getAttribute('src')).toBe('/static/xiaoya/cat.png');
  });

  it('renders an empty placeholder tile (no broken img) when the field is missing', () => {
    render(
      <CardGridBlockRenderer
        block={block as any}
        runtime={runtime([{ pid: '1', name: '豆豆犬', cover: '' }])}
      />,
    );
    expect(screen.getAllByTestId('card-grid-image').length).toBe(1);
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('fits the configured visible artwork bounds without replacing the original image', () => {
    render(<CardGridBlockRenderer block={{ ...block, imageViewports: {
      '/bee.png': { width: 1024, height: 1024, viewBox: [200, 300, 600, 400] },
    } } as any} runtime={runtime([{ pid: '1', name: 'Bee', cover: '/bee.png' }])} />);
    const image = screen.getByRole('img', { name: 'Bee' });
    expect(image.tagName.toLowerCase()).toBe('svg');
    expect(image.getAttribute('viewBox')).toBe('200 300 600 400');
    expect(image.querySelector('image')?.getAttribute('href')).toBe('/bee.png');
    expect(image.querySelector('image')?.getAttribute('width')).toBe('1024');
    expect(image.querySelector('image')?.getAttribute('height')).toBe('1024');
  });

  it.each([
    { width: 1024, height: 1024, viewBox: [-1, 0, 600, 400] },
    { width: 1024, height: 1024, viewBox: [0, 0, 0, 400] },
    { width: 1024, height: 1024, viewBox: [500, 0, 600, 400] },
    { width: Infinity, height: 1024, viewBox: [0, 0, 600, 400] },
    { width: 1024, height: 1024, viewBox: [0, 0, '600', 400] },
  ])('keeps the original image behavior for invalid viewport %j', viewport => {
    render(<CardGridBlockRenderer block={{ ...block, imageViewports: { '/bee.png': viewport } } as any}
      runtime={runtime([{ pid: '1', name: 'Bee', cover: '/bee.png' }])} />);
    expect(screen.getByRole('img', { name: 'Bee' }).tagName.toLowerCase()).toBe('img');
  });

  it('does not apply another image URL viewport to an unknown uploaded cover', () => {
    render(<CardGridBlockRenderer block={{ ...block, imageViewports: {
      '/bee.png': { width: 1024, height: 1024, viewBox: [200, 300, 600, 400] },
    } } as any} runtime={runtime([{ pid: '1', name: 'Upload', cover: '/upload.png' }])} />);
    expect(screen.getByRole('img', { name: 'Upload' }).getAttribute('src')).toBe('/upload.png');
  });

  it('renders no tile at all when imageField is not configured', () => {
    render(
      <CardGridBlockRenderer
        block={{ id: 'grid', blockType: 'card-grid', dataSource: 'rows', titleField: 'name' } as any}
        runtime={runtime([{ pid: '1', name: '月月兔', cover: '/x.png' }])}
      />,
    );
    expect(screen.queryByTestId('card-grid-image')).toBeNull();
  });
});
