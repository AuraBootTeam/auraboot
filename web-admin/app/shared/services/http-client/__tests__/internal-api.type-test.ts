import { internalRequest } from '../internal-api';
import type { InternalRequestInput } from '../internal-api';

// Type-only conformance examples; this function is never executed by a runner.
function consumer() {
  internalRequest('get', '/api/views/{pid}', { path: { pid: 'view-pid' } });
  internalRequest('get', '/api/dynamic/{pageKey}/list', {
    path: { pageKey: 'example' },
    query: { pageNum: 1, pageSize: 20 },
  });
  // @ts-expect-error a path parameter cannot be omitted
  internalRequest('get', '/api/views/{pid}');
  // @ts-expect-error the server does not accept a legacy size query key
  internalRequest('get', '/api/dynamic/{pageKey}/list', {
    path: { pageKey: 'example' },
    query: { size: 20 },
  });
  // @ts-expect-error the path is not a backend operation
  internalRequest('get', '/api/nonexistent-internal-api');
  // @ts-expect-error a declared create body is required
  internalRequest('post', '/api/meta/dict');
  // @ts-expect-error GET-only endpoints cannot be requested with POST
  internalRequest('post', '/api/views/{pid}', { path: { pid: 'view-pid' } });
  // @ts-expect-error a file is not a JSON node
  internalRequest('post', '/api/meta/dict', {
    body: {
      code: 'code',
      name: 'Name',
      dictType: 'simple',
      sourceType: 'STATIC',
      sourceConfig: new File([], 'file'),
    },
  });
  // @ts-expect-error validation requires a dictionary code and name
  const missing: InternalRequestInput<'post', '/api/meta/dict'> = { body: {} };
  // @ts-expect-error response schema fields cannot be substituted for the request DTO
  internalRequest('post', '/api/meta/dict', { body: { pid: 'wrong-input' } });
  return missing;
}
export type InternalConsumerFixture = typeof consumer;
