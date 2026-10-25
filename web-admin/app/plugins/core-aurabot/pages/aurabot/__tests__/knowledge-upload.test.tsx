import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => {
  const success = vi.fn();
  const error = vi.fn();
  return { get: vi.fn(), success, error, toast: { showSuccessToast: success, showErrorToast: error } };
});
vi.mock('react-router', () => ({ useParams: () => ({ kbPid: 'kb-upload' }), useNavigate: () => vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get: mocks.get, post: vi.fn(), del: vi.fn() }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ t: (_key: string, _params: unknown, fallback: string) => fallback }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => mocks.toast }));
import KnowledgeBaseDetail from '../knowledge.$kbPid';

describe('Knowledge document upload result', () => {
  afterEach(() => vi.unstubAllGlobals());
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.get.mockImplementation(async (url: string) => ({ data: url.endsWith('/documents') || url.endsWith('/index-releases') ? [] : {
      pid: 'kb-upload', name: 'Upload contract', status: 'active', embeddingProvider: 'local', embeddingModel: 'actual-model', docCount: 0, chunkCount: 0,
    } }));
  });

  async function upload(body: unknown, ok = true) {
    const json = vi.fn().mockResolvedValue(body);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok, json }));
    render(<KnowledgeBaseDetail />);
    const input = await screen.findByTestId('kb-upload-input');
    fireEvent.change(input, { target: { files: [new File(['real text'], 'policy.txt', { type: 'text/plain' })] } });
    return json;
  }

  it('does not claim success for an HTTP 200 business rejection', async () => {
    const json = await upload({ code: 'UNSUPPORTED_FILE', message: 'rejected' });
    await waitFor(() => expect(mocks.error).toHaveBeenCalledWith('Upload failed'));
    expect(json).toHaveBeenCalledOnce();
    expect(mocks.success).not.toHaveBeenCalled();
  });

  it('reads the accepted result before reporting that processing started', async () => {
    const json = await upload({ code: '0', data: { pid: 'new-document', status: 'pending' } });
    await waitFor(() => expect(mocks.success).toHaveBeenCalledWith('1 file(s) uploaded — processing started'));
    expect(json).toHaveBeenCalledOnce();
    expect(mocks.error).not.toHaveBeenCalled();
  });

  it('keeps a non-200 response in the failure path', async () => {
    await upload({ code: 'DENIED' }, false);
    await waitFor(() => expect(mocks.error).toHaveBeenCalledWith('Upload failed'));
    expect(mocks.success).not.toHaveBeenCalled();
  });
});
