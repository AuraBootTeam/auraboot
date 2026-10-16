import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { attachmentDownloadUrl, FileAttachmentField } from '../FileAttachmentField';
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showErrorToast: vi.fn() }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US' }) }));
describe('Authenticated attachment links', () => {
  it('routes local file download URLs through the BFF', () => {
    expect(attachmentDownloadUrl('/download/42')).toBe('/api/file/download/42');
    expect(attachmentDownloadUrl('/api/file/download/42')).toBe('/api/file/download/42');
    expect(attachmentDownloadUrl('javascript:alert(1)')).toBeNull();
    expect(attachmentDownloadUrl('//external.test/secret')).toBeNull();
  });
  it('displays actual filename, size and download target', () => {
    render(<FileAttachmentField readOnly value={[{ name: 'scope.pdf', url: '/download/42', size: 768 }]} />);
    expect(screen.getByTestId('attachment-download-0')).toHaveAttribute('href', '/api/file/download/42');
    expect(screen.getByTestId('attachment-download-0')).toHaveTextContent('scope.pdf');
    expect(screen.getByTestId('attachment-download-0')).toHaveTextContent('768 B');
  });
});
