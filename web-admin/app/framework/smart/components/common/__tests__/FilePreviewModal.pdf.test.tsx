import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { FilePreviewModal } from '../FilePreviewModal';

afterEach(cleanup);

describe('native PDF rendering boundary', () => {
  const frame = () => screen.getByRole('dialog').querySelector('iframe')!;
  const base = {
    open: true,
    onClose: () => {},
    fileName: 'purchase.pdf',
    fileType: 'application/pdf',
    fileUrl: 'blob:http://localhost/verified-pdf',
  };

  it('keeps the historical sandbox for ordinary file previews', () => {
    render(<FilePreviewModal {...base} />);
    expect(frame().getAttribute('sandbox')).toBe(
      'allow-scripts allow-same-origin',
    );
  });

  it('allows the browser PDF viewer only for an explicitly verified PDF Blob', () => {
    render(<FilePreviewModal {...base} verifiedPdfBlob />);
    expect(frame().hasAttribute('sandbox')).toBe(false);
  });

  it.each([
    { fileUrl: '/api/file/download/unverified' },
    { fileUrl: 'https://external.invalid/unverified.pdf' },
    { fileType: 'text/html' },
    { fileType: undefined },
  ])('retains isolation when the opt-in has an incompatible source: %j', (change) => {
    render(<FilePreviewModal {...base} {...change} verifiedPdfBlob />);
    expect(frame().getAttribute('sandbox')).toBe(
      'allow-scripts allow-same-origin',
    );
  });
});
