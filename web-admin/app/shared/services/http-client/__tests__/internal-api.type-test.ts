import { internalRequest, internalFormRequest } from '../internal-api';
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
  internalRequest('get', '/api/dynamic/{pageKey}/list', {
    path: { pageKey: 'example' },
    // @ts-expect-error the server does not accept a legacy size query key
    query: { size: 20 },
  });
  // @ts-expect-error the path is not a backend operation
  internalRequest('get', '/api/nonexistent-internal-api');
  // @ts-expect-error a declared create body is required
  internalRequest('post', '/api/meta/dict');
  // @ts-expect-error GET-only endpoints cannot be requested with POST
  internalRequest('post', '/api/views/{pid}', { path: { pid: 'view-pid' } });
  internalRequest('post', '/api/meta/dict', {
    body: {
      code: 'code',
      name: 'Name',
      dictType: 'simple',
      sourceType: 'STATIC',
      // @ts-expect-error a file is not a JSON node
      sourceConfig: new File([], 'file'),
    },
  });
  // @ts-expect-error validation requires a dictionary code and name
  const missing: InternalRequestInput<'post', '/api/meta/dict'> = { body: {} };
  // @ts-expect-error response schema fields cannot be substituted for the request DTO
  internalRequest('post', '/api/meta/dict', { body: { pid: 'wrong-input' } });
  internalRequest('get', '/api/user/profile');
  internalRequest('get', '/api/permission/capabilities', { query: { rolePid: 'role-pid' } });
  internalRequest('post', '/api/permission/capabilities/preview', { query: { rolePid: 'role-pid', revokePartial: ['org.cap.edit'] }, body: ['org.cap.read'] });
  internalRequest('put', '/api/permission/capabilities', { query: { rolePid: 'role-pid' }, body: [] });
  // @ts-expect-error capability selection requires a real JSON array
  internalRequest('put', '/api/permission/capabilities', { query: { rolePid: 'role-pid' }, body: { codes: [] } });
  // @ts-expect-error capability requests require the public role PID query
  internalRequest('get', '/api/permission/capabilities');
  // @ts-expect-error legacy numeric IDs cannot replace the role PID
  internalRequest('get', '/api/permission/capabilities', { query: { rolePid: 123 } });
  // @ts-expect-error only capability code strings can be revoked
  internalRequest('post', '/api/permission/capabilities/preview', { query: { rolePid: 'role-pid', revokePartial: [123] }, body: [] });
  internalRequest('get', '/api/roles/{rolePid}/members', {path:{rolePid:'role-pid'},query:{pageNum:1,pageSize:20}});
  internalRequest('post', '/api/roles/{rolePid}/members/remove', {path:{rolePid:'role-pid'},body:['member-pid']});
  internalRequest('post', '/api/roles/{rolePid}/members/assign', {path:{rolePid:'role-pid'},body:{memberPids:['member-pid'],expiryDate:null}});
  // @ts-expect-error role membership writes use member PID strings
  internalRequest('post', '/api/roles/{rolePid}/members/remove', {path:{rolePid:'role-pid'},body:[123]});
  // @ts-expect-error the backend list query accepts pageNum rather than page
  internalRequest('get', '/api/roles/{rolePid}/members', {path:{rolePid:'role-pid'},query:{page:1}});
  // @ts-expect-error role PID cannot be substituted by a legacy numeric role ID
  internalRequest('get', '/api/roles/{rolePid}/members', {path:{rolePid:123}});
  internalRequest('put', '/api/user/profile', { body: { imgId: 'file-owned' } });
  internalFormRequest('post', '/api/user/avatar/upload', { body: { file: new Blob(['image']) } });
  // @ts-expect-error the authenticated identity is injected by the server
  internalRequest('get', '/api/user/profile', { query: { userId: 'other-user' } });
  // @ts-expect-error avatar bytes cannot be supplied through the JSON operation
  internalRequest('post', '/api/user/avatar/upload', { body: { file: new Blob([]) } });
  // @ts-expect-error multipart file is required
  internalFormRequest('post', '/api/user/avatar/upload', { body: {} });
  // @ts-expect-error binary multipart parts are Blob/File values, not strings
  internalFormRequest('post', '/api/user/avatar/upload', { body: { file: 'file-string' } });
  internalFormRequest('post', '/api/user/avatar/upload', {
    body: { file: new Blob([]) },
    // @ts-expect-error JSON options cannot override the generated multipart body
    options: { body: {} },
  });
  return missing;
}
export type InternalConsumerFixture = typeof consumer;
