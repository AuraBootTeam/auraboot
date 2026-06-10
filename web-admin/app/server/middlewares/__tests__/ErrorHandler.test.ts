/**
 * Unit tests for ErrorHandler middleware
 * Covers AxiosError, BffError, ValidationError, generic error paths,
 * createBffError helper, and asyncErrorHandler wrapper.
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';

vi.mock('~/server/utils/logger', () => ({
  default: { error: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

import {
  ErrorHandlerMiddleware,
  createBffError,
  asyncErrorHandler,
  type BffError,
} from '../ErrorHandler';
import { AxiosError } from 'axios';

// ── Helpers ────────────────────────────────────────────────────────────────

function mockReq(overrides: Record<string, unknown> = {}) {
  return {
    method: 'GET',
    path: '/test',
    headers: { 'user-agent': 'test-agent' },
    ip: '127.0.0.1',
    connection: { remoteAddress: '127.0.0.1' },
    ...overrides,
  } as any;
}

function mockRes() {
  const res: any = {};
  res.status = vi.fn().mockReturnValue(res);
  res.json = vi.fn().mockReturnValue(res);
  return res;
}

function mockNext() {
  return vi.fn() as any;
}

// ── AxiosError factory ─────────────────────────────────────────────────────

function makeAxiosError(opts: {
  response?: { status: number; data?: any };
  request?: boolean;
  message?: string;
} = {}): AxiosError {
  const error = new AxiosError(opts.message || 'axios error');
  if (opts.response) {
    (error as any).response = { status: opts.response.status, data: opts.response.data ?? {} };
  } else if (opts.request) {
    (error as any).request = {};
  }
  error.isAxiosError = true;
  return error;
}

describe('ErrorHandlerMiddleware', () => {
  let handler: ErrorHandlerMiddleware;

  beforeEach(() => {
    handler = new ErrorHandlerMiddleware();
    vi.clearAllMocks();
  });

  // ── AxiosError paths ──────────────────────────────────────────────────────

  describe('AxiosError: response received', () => {
    it('forwards backend status code and extracts message from data.message', () => {
      const error = makeAxiosError({ response: { status: 503, data: { message: 'Service down' } } });
      const res = mockRes();

      handler.middleware(error, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(503);
      const body = res.json.mock.calls[0][0];
      expect(body.error).toBe('Backend Service Error');
      expect(body.message).toBe('Service down');
      expect(typeof body.requestId).toBe('string');
    });

    it('extracts message from data.error fallback', () => {
      const error = makeAxiosError({ response: { status: 400, data: { error: 'Bad input' } } });
      const res = mockRes();

      handler.middleware(error, mockReq(), res, mockNext());

      const body = res.json.mock.calls[0][0];
      expect(body.message).toBe('Bad input');
    });

    it('falls back to axios message when data has no message fields', () => {
      const error = makeAxiosError({ response: { status: 500, data: null }, message: 'server down' });
      const res = mockRes();

      handler.middleware(error, mockReq(), res, mockNext());

      const body = res.json.mock.calls[0][0];
      expect(body.message).toBe('server down');
    });

    it('handles string data response', () => {
      const error = makeAxiosError({ response: { status: 422, data: 'Unprocessable' } });
      const res = mockRes();

      handler.middleware(error, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(422);
      const body = res.json.mock.calls[0][0];
      expect(body.message).toBe('Unprocessable');
    });
  });

  describe('AxiosError: no response (request made)', () => {
    it('returns 503 Service Unavailable', () => {
      const error = makeAxiosError({ request: true });
      const res = mockRes();

      handler.middleware(error, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(503);
      const body = res.json.mock.calls[0][0];
      expect(body.error).toBe('Service Unavailable');
    });
  });

  describe('AxiosError: no response, no request (config error)', () => {
    it('returns 500 Request Configuration Error', () => {
      const error = makeAxiosError({ message: 'config error' });
      const res = mockRes();

      handler.middleware(error, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(500);
      const body = res.json.mock.calls[0][0];
      expect(body.error).toBe('Request Configuration Error');
    });
  });

  // ── BffError path ─────────────────────────────────────────────────────────

  describe('BffError (custom error with status/code)', () => {
    it('uses error.status and includes code + details', () => {
      const bff = createBffError('Not enough credit', 402, 'CREDIT_EXHAUSTED', { remaining: 0 });
      const res = mockRes();

      handler.middleware(bff, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(402);
      const body = res.json.mock.calls[0][0];
      expect(body.code).toBe('CREDIT_EXHAUSTED');
      expect(body.details).toEqual({ remaining: 0 });
    });

    it('defaults to 500 when status is not set', () => {
      const bff = new Error('oops') as BffError;
      bff.code = 'ERR_X';
      const res = mockRes();

      handler.middleware(bff, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(500);
    });
  });

  // ── ValidationError path ──────────────────────────────────────────────────

  describe('ValidationError', () => {
    it('returns 400 for error.name === ValidationError', () => {
      const err: any = new Error('Invalid payload');
      err.name = 'ValidationError';
      err.details = [{ field: 'email', message: 'required' }];
      const res = mockRes();

      handler.middleware(err, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(400);
      const body = res.json.mock.calls[0][0];
      expect(body.error).toBe('Validation Error');
      expect(body.details).toEqual(err.details);
    });

    it('returns 400 when error has errors array', () => {
      const err: any = new Error('validation failed');
      err.errors = ['field required'];
      const res = mockRes();

      handler.middleware(err, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(400);
    });
  });

  // ── Generic error path ────────────────────────────────────────────────────

  describe('Generic error', () => {
    it('returns 500 and does NOT expose internal message', () => {
      const err = new Error('internal secret details');
      const res = mockRes();

      handler.middleware(err, mockReq(), res, mockNext());

      expect(res.status).toHaveBeenCalledWith(500);
      const body = res.json.mock.calls[0][0];
      expect(body.error).toBe('Internal Server Error');
      expect(body.message).not.toContain('secret');
    });

    it('includes a requestId from x-request-id header', () => {
      const err = new Error('fail');
      const req = mockReq({ headers: { 'x-request-id': 'req-abc-123' } });
      const res = mockRes();

      handler.middleware(err, req, res, mockNext());

      const body = res.json.mock.calls[0][0];
      expect(body.requestId).toBe('req-abc-123');
    });

    it('generates a requestId when header is missing', () => {
      const err = new Error('fail');
      const res = mockRes();

      handler.middleware(err, mockReq(), res, mockNext());

      const body = res.json.mock.calls[0][0];
      expect(typeof body.requestId).toBe('string');
      expect(body.requestId.length).toBeGreaterThan(0);
    });
  });
});

// ── createBffError ────────────────────────────────────────────────────────────

describe('createBffError', () => {
  it('creates Error with status, code, and details', () => {
    const err = createBffError('Resource locked', 423, 'LOCKED', { resource: 'flow' });
    expect(err).toBeInstanceOf(Error);
    expect(err.message).toBe('Resource locked');
    expect(err.status).toBe(423);
    expect(err.code).toBe('LOCKED');
    expect(err.details).toEqual({ resource: 'flow' });
  });

  it('defaults status to 500 when not provided', () => {
    const err = createBffError('Oops');
    expect(err.status).toBe(500);
  });
});

// ── asyncErrorHandler ─────────────────────────────────────────────────────────

describe('asyncErrorHandler', () => {
  it('calls next with the error when the async handler rejects', async () => {
    const thrownError = new Error('async failure');
    const asyncFn = vi.fn().mockRejectedValue(thrownError);
    const wrapped = asyncErrorHandler(asyncFn);

    const next = mockNext();
    await wrapped(mockReq(), mockRes(), next);

    expect(next).toHaveBeenCalledWith(thrownError);
  });

  it('does NOT call next when the async handler resolves', async () => {
    const asyncFn = vi.fn().mockResolvedValue(undefined);
    const wrapped = asyncErrorHandler(asyncFn);

    const next = mockNext();
    await wrapped(mockReq(), mockRes(), next);

    expect(next).not.toHaveBeenCalled();
  });
});
