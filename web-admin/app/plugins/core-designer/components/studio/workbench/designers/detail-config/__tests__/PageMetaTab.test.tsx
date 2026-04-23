import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { PageMetaTab, normalizeTitleValue } from '../PageMetaTab';

describe('normalizeTitleValue', () => {
  it('converts non-ASCII collapsed input into zh-CN localized text', () => {
    expect(normalizeTitleValue('请假申请详情', undefined)).toEqual({
      'zh-CN': '请假申请详情',
    });
  });

  it('preserves existing en-US title when updating zh-CN in collapsed mode', () => {
    expect(
      normalizeTitleValue('请假申请详情', {
        'en-US': 'Leave Request Detail',
      } as any),
    ).toEqual({
      'zh-CN': '请假申请详情',
      'en-US': 'Leave Request Detail',
    });
  });

  it('keeps ascii title as plain string for backward compatibility', () => {
    expect(normalizeTitleValue('Leave Request Detail', undefined)).toBe('Leave Request Detail');
  });

  it('does not lose title when pageKey is edited immediately afterwards', () => {
    const onSchemaChange = vi.fn();

    render(
      <PageMetaTab
        schema={{
          schemaVersion: 2,
          kind: 'detail',
          id: 'p1',
          pageKey: 'leave_detail',
          title: { en: 'Leave Detail' },
          layout: { type: 'stack' },
          blocks: [],
        } as any}
        onSchemaChange={onSchemaChange}
      />,
    );

    fireEvent.change(screen.getByTestId('detail-page-title-input-zh'), {
      target: { value: '请假申请详情' },
    });
    fireEvent.change(screen.getByTestId('detail-page-key-input'), {
      target: { value: 'leave_detail_v2' },
    });

    expect(onSchemaChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pageKey: 'leave_detail_v2',
        title: {
          en: 'Leave Detail',
          'zh-CN': '请假申请详情',
        },
      }),
    );
  });
});
