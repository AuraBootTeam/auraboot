import type { InternalResponseData } from '~/shared/services/http-client/internal-api';

/** Wire types come from the exact capability operations; no handwritten response mirror. */
export type CapabilityGroup = InternalResponseData<'get', '/api/permission/capabilities'>[number];
export type Capability = CapabilityGroup['capabilities'][number];
export type CapabilitySelectionPreview = InternalResponseData<
  'post',
  '/api/permission/capabilities/preview'
>;
