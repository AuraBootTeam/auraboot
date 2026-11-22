import { describe, expect, it } from 'vitest';
import { snapshotPromptPresentation } from '../promptPresentation';
import { currentFormDialogScope, registerFormDialogScope } from '../formDialogLifecycle';
import type { CommandInputPresentation } from '~/framework/meta/schemas/types';
describe('readonly command prompt snapshot', () => {
  it('accepts only file PIDs for a readonly image, preserves omission, and freezes the identity', () => {
    const record = { imageFilePid: '01J00000000000000000000001' };
    const presentation = {
      summary: { fields: [{ field: 'imageFilePid', renderAs: 'file-image' as const }] },
    };
    const result = snapshotPromptPresentation('Edit', presentation, { record });
    record.imageFilePid = '01J00000000000000000000002';
    expect(result.presentation?.summary?.items[0]).toMatchObject({
      value: '01J00000000000000000000001',
      renderAs: 'file-image',
    });
    expect(
      snapshotPromptPresentation('Edit', presentation, {}).presentation?.summary?.items[0].value,
    ).toBe('—');
    for (const value of [
      'https://external.test/image.png',
      '/api/file/download/other',
      '../private',
      '<img src=x>',
      'not-a-pid',
    ]) {
      expect(() =>
        snapshotPromptPresentation('Edit', presentation, { record: { imageFilePid: value } }),
      ).toThrow('Invalid command input image reference');
    }
  });
  it('captures title and readonly values without retaining mutable record references', () => {
    const record = { title: 'Case A', status: 'new', secret: { nested: true } };
    const snapshot = snapshotPromptPresentation(
      { en: 'Close · ${record.title}' },
      {
        width: 600,
        showClose: true,
        summary: {
          columns: 2,
          fields: [
            { field: 'title', label: 'Event' },
            { field: 'status', dictCode: 'status' },
            { field: 'secret' },
            { field: 'missing' },
          ],
        },
      },
      { record },
      { locale: 'en' },
    );
    record.title = 'Case B';
    record.status = 'closed';
    expect(snapshot.title).toBe('Close · Case A');
    expect(snapshot.presentation?.summary?.items.map((item) => item.value)).toEqual([
      'Case A',
      'new',
      '—',
      '—',
    ]);
  });
  it('preserves old callers and never evaluates JavaScript from a title template', () => {
    expect(snapshotPromptPresentation('Old title', undefined, {})).toEqual({ title: 'Old title' });
    expect(
      snapshotPromptPresentation(
        '${record.constructor} / ${record.title.toUpperCase()}',
        undefined,
        { record: { title: 'Case A' } },
      ).title,
    ).toBe('— / —');
  });
  it.each([
    { width: 0 },
    { width: Infinity },
    { showClose: 'yes' },
    { closeOnBackdrop: 'no' },
    { summary: { columns: 3, fields: [] } },
    { summary: { fields: [{ field: '__proto__' }] } },
    { summary: { fields: [{ field: 'image', renderAs: 'html' }] } },
    { summary: { fields: [{ field: 'image', renderAs: 'file-image', dictCode: 'status' }] } },
  ])('rejects invalid runtime presentation %j', (config) => {
    expect(() =>
      snapshotPromptPresentation('Title', config as CommandInputPresentation, {}),
    ).toThrow();
  });
  it('keeps legacy locale maps when no caller locale was provided while freezing every template', () => {
    const result = snapshotPromptPresentation(
      { en: 'Close ${record.title}', 'zh-CN': '关闭${record.title}' },
      undefined,
      { record: { title: 'A' } },
    );
    expect(result.title).toEqual({ en: 'Close A', 'zh-CN': '关闭A' });
  });
  it('keeps the modal scope ahead of a later root layout effect and restores the parent scope', () => {
    const modal = new AbortController(),
      root = new AbortController();
    const removeModal = registerFormDialogScope(modal.signal, 1);
    const removeRoot = registerFormDialogScope(root.signal);
    try {
      expect(currentFormDialogScope()).toBe(modal.signal);
      removeModal();
      expect(currentFormDialogScope()).toBe(root.signal);
    } finally {
      removeModal();
      removeRoot();
    }
  });
});

it('preserves explicit backdrop retention through the prompt snapshot and leaves omission unset', () => {
  expect(
    snapshotPromptPresentation('Close', { closeOnBackdrop: false }, {}).presentation
      ?.closeOnBackdrop,
  ).toBe(false);
  expect(snapshotPromptPresentation('Legacy', {}, {}).presentation).not.toHaveProperty(
    'closeOnBackdrop',
  );
});
