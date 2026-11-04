import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { attachmentDownloadUrl, FileAttachmentField } from '../FileAttachmentField';
const mocks = vi.hoisted(() => ({ fetchResult: vi.fn(), uploadFile: vi.fn(), showErrorToast: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: mocks.fetchResult }));
vi.mock('~/shared/services/fileupload/uploadService', () => ({ uploadFile: mocks.uploadFile }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showErrorToast: mocks.showErrorToast }) }));
beforeEach(() => { vi.clearAllMocks(); });
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US' }) }));
describe('Authenticated attachment links', () => {
  it('routes local file download URLs through the BFF', () => {
    expect(attachmentDownloadUrl('/download/42')).toBe('/api/file/download/42');
    expect(attachmentDownloadUrl('/api/file/download/42')).toBe('/api/file/download/42');
    expect(attachmentDownloadUrl('javascript:alert(1)')).toBeNull();
    expect(attachmentDownloadUrl('//external.test/secret')).toBeNull();
  });
  it('keeps authenticated version and approval attachments usable through the same-origin BFF', () => {
    const recipient='/api/announcements/versions/version-1/attachments/file-1';
    const approval='/api/announcements/approvals/submission-1/attachments/file-1';
    expect(attachmentDownloadUrl(recipient)).toBe(recipient);
    expect(attachmentDownloadUrl(approval)).toBe(approval);
    expect(attachmentDownloadUrl('/api/example/documents/record-1/attachments/file-1'))
      .toBe('/api/example/documents/record-1/attachments/file-1');
    render(<FileAttachmentField readOnly value={[{ name: 'frozen-policy.txt', url: recipient, size: 768 }]} />);
    expect(screen.getByTestId('attachment-download-0')).toHaveAttribute('href', recipient);
    expect(screen.getByTestId('attachment-download-0')).toHaveAttribute('aria-disabled', 'false');
  });
  it('rejects arbitrary API navigation, traversal and encoded path changes', () => {
    for (const value of ['/api/admin/users', '/api/announcements/versions/../attachments/file-1',
      '/api/announcements/versions/%2e%2e/attachments/file-1',
      '/api/announcements/versions/version-1/attachments/%2fsecret',
      '/api/announcements/versions/version-1/attachments/file-1?next=//external.test']) {
      expect(attachmentDownloadUrl(value)).toBeNull();
    }
  });
  it('reads a scalar reference without upload capabilities or changing its stored value', async () => {
    mocks.fetchResult.mockResolvedValue({ code: '0', data: { fileId: '01J4P1X4S5R6T7V8W9X0Y1Z2A3', originalName: 'actual.yaml', fileSize: 15 } });
    const onChange = vi.fn();
    render(<FileAttachmentField readOnly value="01J4P1X4S5R6T7V8W9X0Y1Z2A3" onChange={onChange} />);
    expect(await screen.findByRole('link', { name: 'actual.yaml 15 B' })).toHaveAttribute('href', '/api/file/download/01J4P1X4S5R6T7V8W9X0Y1Z2A3');
    expect(onChange).not.toHaveBeenCalled();
    expect(mocks.uploadFile).not.toHaveBeenCalled();
  });
  it('displays actual filename, size and download target', () => {
    render(<FileAttachmentField readOnly value={[{ name: 'scope.pdf', url: '/download/42', size: 768 }]} />);
    expect(screen.getByTestId('attachment-download-0')).toHaveAttribute('href', '/api/file/download/42');
    expect(screen.getByTestId('attachment-download-0')).toHaveTextContent('scope.pdf');
    expect(screen.getByTestId('attachment-download-0')).toHaveTextContent('768 B');
  });
});

describe('configured upload limits', () => {
  it('uses the effective server limit before allowing a file to enter the upload path', async () => {
    mocks.fetchResult.mockResolvedValue({ code: '0', data: { maxFileBytes: 2 * 1024 * 1024, maxRequestBytes: 3 * 1024 * 1024 } });
    render(<FileAttachmentField limitsEndpoint="/api/file/limits" />);
    await waitFor(() => expect(screen.getByRole('button', { name: '+ Add file' })).toBeEnabled());
    expect(screen.getByText('Current file-size limit: 2 MB')).toBeVisible();
    fireEvent.change(screen.getByTestId('attachment-file-input'), { target: { files: [new File([new Uint8Array(3 * 1024 * 1024)], 'too-large.txt')] } });
    await waitFor(() => expect(mocks.showErrorToast).toHaveBeenCalledWith('too-large.txt exceeds the 2 MB limit.'));
    expect(mocks.uploadFile).not.toHaveBeenCalled();
  });
  it('does not fall back to a hard-coded limit after an unavailable configuration response', async () => {
    mocks.fetchResult.mockResolvedValue({ code: '403' });
    render(<FileAttachmentField limitsEndpoint="/api/file/limits" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Upload limits could not be loaded');
    expect(screen.getByRole('button', { name: '+ Add file' })).toBeDisabled();
    expect(screen.getByTestId('attachment-file-input')).toBeDisabled();
  });
  it('does not require upload capabilities while reading an existing attachment', () => {
    render(<FileAttachmentField readOnly limitsEndpoint="/api/file/limits" value={[{ name: 'scope.txt', url: '/api/file/download/public-file' }]} />);
    expect(screen.getByTestId('attachment-download-0')).toHaveTextContent('scope.txt');
    expect(mocks.fetchResult).not.toHaveBeenCalled();
  });
  it('rejects a selection that would exceed the configured attachment count before uploading', async () => {
    render(<FileAttachmentField maxCount={2} value={[{ name: 'existing.txt', url: '/api/file/download/existing' }]} />);
    fireEvent.change(screen.getByTestId('attachment-file-input'), { target: { files: [new File(['x'], 'another.txt'), new File(['y'], 'third.txt')] } });
    expect(mocks.uploadFile).not.toHaveBeenCalled();
    expect(mocks.showErrorToast).toHaveBeenCalledWith('Select at most 2 attachments.');
  });
});
