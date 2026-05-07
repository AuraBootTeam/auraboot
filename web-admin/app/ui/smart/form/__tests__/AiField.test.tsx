import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiField } from '../AiField';

/**
 * F.3 vitest coverage for AiField vision input.
 *
 * Verifies:
 *  - back-compat: paperclip absent unless `imageInput` prop is true
 *  - selecting a file → preview rendered + base64 carried in next AI request
 *  - removing the staged image → next AI request omits `images`
 *  - successful generation auto-clears staged attachments (one-shot)
 */

// Mock FileReader so the base64 path is deterministic and synchronous-ish.
class MockFileReader {
  result: string | null = null;
  onload: ((this: FileReader, ev: ProgressEvent<FileReader>) => void) | null = null;
  onerror: ((this: FileReader, ev: ProgressEvent<FileReader>) => void) | null = null;
  error: unknown = null;
  readAsDataURL(_file: Blob) {
    this.result = 'data:image/png;base64,FAKE_BASE64_PAYLOAD';
    setTimeout(() => {
      this.onload?.call(this as unknown as FileReader, {} as ProgressEvent<FileReader>);
    }, 0);
  }
}

beforeEach(() => {
  global.FileReader = MockFileReader as unknown as typeof FileReader;
});

afterEach(() => {
  vi.restoreAllMocks();
});

function makeFetchMock() {
  return vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    json: async () => ({
      code: '0',
      success: true,
      data: { content: 'AI response text' },
    }),
  });
}

describe('AiField — F.3 vision input', () => {
  it('does not render paperclip when imageInput prop is unset (back-compat)', () => {
    render(<AiField />);
    expect(screen.queryByTestId('aifield-attach-image')).toBeNull();
    expect(screen.queryByTestId('aifield-image-input')).toBeNull();
  });

  it('renders paperclip and hidden file input when imageInput=true', () => {
    render(<AiField imageInput />);
    expect(screen.getByTestId('aifield-attach-image')).toBeTruthy();
    expect(screen.getByTestId('aifield-image-input')).toBeTruthy();
  });

  it('selecting a file shows preview and includes base64 in next AI request', async () => {
    const fetchMock = makeFetchMock();
    global.fetch = fetchMock;

    const onChange = vi.fn();
    render(<AiField imageInput onChange={onChange} />);

    const file = new File(['ignored'], 'receipt.png', { type: 'image/png' });
    const input = screen.getByTestId('aifield-image-input') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    fireEvent.change(input);

    // Preview should appear.
    await waitFor(() => {
      expect(screen.getByTestId('aifield-attachments-preview')).toBeTruthy();
      expect(screen.getByTestId('aifield-attachment-0')).toBeTruthy();
    });

    // Click main AI button.
    const aiButton = screen.getByRole('button', { name: /AI Generate/i });
    fireEvent.click(aiButton);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

    const [, init] = fetchMock.mock.calls[0];
    const body = JSON.parse((init as RequestInit).body as string);
    expect(body.images).toBeDefined();
    expect(body.images).toHaveLength(1);
    expect(body.images[0]).toEqual({
      mediaType: 'image/png',
      data: 'FAKE_BASE64_PAYLOAD',
    });

    // After success the AiField clears staged attachments.
    await waitFor(() =>
      expect(screen.queryByTestId('aifield-attachments-preview')).toBeNull(),
    );
  });

  it('removing the image → next AI request has no images field', async () => {
    const fetchMock = makeFetchMock();
    global.fetch = fetchMock;

    render(<AiField imageInput />);

    const file = new File(['x'], 'a.png', { type: 'image/png' });
    const input = screen.getByTestId('aifield-image-input') as HTMLInputElement;
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    fireEvent.change(input);

    await waitFor(() => screen.getByTestId('aifield-attachment-0'));

    fireEvent.click(screen.getByTestId('aifield-attachment-remove-0'));
    await waitFor(() =>
      expect(screen.queryByTestId('aifield-attachments-preview')).toBeNull(),
    );

    const aiButton = screen.getByRole('button', { name: /AI Generate/i });
    fireEvent.click(aiButton);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.images).toBeUndefined();
  });

  it('imageInput=false (default) → AI request contains no images field', async () => {
    const fetchMock = makeFetchMock();
    global.fetch = fetchMock;

    render(<AiField />);
    const aiButton = screen.getByRole('button', { name: /AI Generate/i });
    fireEvent.click(aiButton);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.images).toBeUndefined();
  });
});
