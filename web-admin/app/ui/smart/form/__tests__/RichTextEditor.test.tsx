import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { RichTextEditor } from '../RichTextEditor';

vi.mock('react-router', async original => ({ ...await original<typeof import('react-router')>(), useActionData: () => undefined }));
vi.mock('~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent', () => ({
  useSmartField: ({ value, onChange }: any) => ({ value, setValue: onChange }),
}));
vi.mock('~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract', () => ({
  useSmartFieldContract: ({ label, placeholder, disabled }: any) => ({ labelText: label, placeholderText: placeholder, disabled, disabledValue: disabled, visible: true, isVisible: true }),
}));
vi.mock('~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta', () => ({
  useSmartFieldMeta: () => ({ meta: {}, showError: false }),
}));
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (value: string) => value }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('~/ui/ui/field-base', () => ({ FieldBase: ({ children }: any) => <div>{children}</div> }));

describe('shared rich text editor value synchronization', () => {
  it('loads the current record, updates a later value without emitting a write, and localizes formatting controls', async () => {
    const change = vi.fn();const view = render(<RichTextEditor name="body" label="正文" value="<p>Original</p>" onChange={change} />);
    await waitFor(() => expect(screen.getByRole('textbox', { name: '正文' })).toHaveTextContent('Original'));
    expect(screen.getByRole('button', { name: '加粗' })).toBeVisible();
    view.rerender(<RichTextEditor name="body" label="正文" value="<p><strong>Updated</strong></p>" onChange={change} />);
    await waitFor(() => expect(screen.getByRole('textbox')).toHaveTextContent('Updated'));
    expect(screen.getByRole('textbox').querySelector('strong')).toHaveTextContent('Updated');
    expect(change).not.toHaveBeenCalled();
  });

  it('shows sanitized rich text in read-only mode', () => {
    const view = render(<RichTextEditor name="body" label="正文" value={'<p>Policy</p><img src="/image.png" onerror="alert(1)"><script>alert(1)</script>'} readOnly />);
    expect(screen.getByText('Policy')).toBeVisible();
    expect(view.container.querySelector('script,img[onerror]')).toBeNull();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });
});
