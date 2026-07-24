// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { shouldForwardRequestBody } from '../BffProxyService';

describe('shouldForwardRequestBody', () => {
  it('forwards an empty array body (a deliberate client payload)', () => {
    // Clearing the last automation-debug breakpoint sends `PUT []`. express.json() never
    // defaults a body-less request to `[]` (it uses `{}`), so an empty array is always a
    // real payload — dropping it made the backend 400 with "Required request body is missing".
    expect(shouldForwardRequestBody('PUT', [])).toBe(true);
    expect(shouldForwardRequestBody('POST', [1, 2])).toBe(true);
  });

  it('does not forward an empty object (a body-less request parsed to {})', () => {
    // Guarded on purpose: express turns a body-less POST into `{}`; forwarding it desyncs
    // Content-Length and corrupts the next keep-alive request (the framing bug).
    expect(shouldForwardRequestBody('POST', {})).toBe(false);
  });

  it('forwards a non-empty object body', () => {
    expect(shouldForwardRequestBody('POST', { a: 1 })).toBe(true);
  });

  it('never forwards a body for GET/HEAD', () => {
    expect(shouldForwardRequestBody('GET', [1])).toBe(false);
    expect(shouldForwardRequestBody('HEAD', { a: 1 })).toBe(false);
  });

  it('does not forward null/undefined/empty-string bodies', () => {
    expect(shouldForwardRequestBody('POST', null)).toBe(false);
    expect(shouldForwardRequestBody('POST', undefined)).toBe(false);
    expect(shouldForwardRequestBody('POST', '')).toBe(false);
  });
});
