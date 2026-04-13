import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { blockAcceptsFieldLikeDrop, getCanvasDragKind, useCanvasDnd } from '../useCanvasDnd';

describe('getCanvasDragKind', () => {
  it('classifies widget drags', () => {
    expect(getCanvasDragKind('widget:text')).toBe('widget');
  });

  it('classifies field drags', () => {
    expect(getCanvasDragKind('field:customer_name')).toBe('field');
  });

  it('classifies existing field item drags as field drags', () => {
    expect(getCanvasDragKind('field-item:block_1:0')).toBe('field');
  });

  it('classifies palette drags', () => {
    expect(getCanvasDragKind('palette:table')).toBe('palette');
  });

  it('treats unknown ids as block drags', () => {
    expect(getCanvasDragKind('block_123')).toBe('block');
  });

  it('returns null when no drag is active', () => {
    expect(getCanvasDragKind(null)).toBeNull();
  });
});

describe('blockAcceptsFieldLikeDrop', () => {
  it('accepts widget/field drops for form sections', () => {
    expect(blockAcceptsFieldLikeDrop('form-section')).toBe(true);
  });

  it('rejects widget/field drops for non-form blocks', () => {
    expect(blockAcceptsFieldLikeDrop('table')).toBe(false);
    expect(blockAcceptsFieldLikeDrop('chart')).toBe(false);
    expect(blockAcceptsFieldLikeDrop('toolbar')).toBe(false);
  });
});

describe('useCanvasDnd', () => {
  it('inserts a widget into a form-section slot', () => {
    const updateBlock = vi.fn();
    const { result } = renderHook(() =>
      useCanvasDnd({
        blocks: [
          {
            id: 'block_1',
            blockType: 'form-section',
            config: { fields: ['name', 'email'] },
          } as any,
        ],
        addBlock: vi.fn() as any,
        addBlockAt: vi.fn() as any,
        moveBlock: vi.fn(),
        updateBlock,
      }),
    );

    act(() => {
      result.current.handleDragEnd({
        active: { id: 'widget:text' },
        over: { id: 'field-slot:block_1:1' },
      } as any);
    });

    expect(updateBlock).toHaveBeenCalledTimes(1);
    expect(updateBlock.mock.calls[0][0]).toBe('block_1');
    expect(updateBlock.mock.calls[0][1].config.fields).toHaveLength(3);
    expect(updateBlock.mock.calls[0][1].config.fields[1]).toMatchObject({ component: 'text' });
  });

  it('reorders fields when dropped on an insertion slot in the same block', () => {
    const reorderFields = vi.fn();
    const { result } = renderHook(() =>
      useCanvasDnd({
        blocks: [
          {
            id: 'block_1',
            blockType: 'form-section',
            config: { fields: ['name', 'email', 'phone'] },
          } as any,
        ],
        addBlock: vi.fn() as any,
        addBlockAt: vi.fn() as any,
        moveBlock: vi.fn(),
        updateBlock: vi.fn(),
        reorderFields,
      }),
    );

    act(() => {
      result.current.handleDragEnd({
        active: { id: 'field-item:block_1:0' },
        over: { id: 'field-slot:block_1:2' },
      } as any);
    });

    expect(reorderFields).toHaveBeenCalledWith('block_1', 0, 1);
  });
});
