import type {
  InternalRequestInput,
  InternalResponseData,
} from '~/shared/services/http-client/internal-api';

/** Generated HTTP shapes. Editor state and display projections live in savedView.ts. */
export type SavedViewWire = InternalResponseData<'get', '/api/views/{pid}'>;
export type SavedViewConfigWire = NonNullable<SavedViewWire['viewConfig']>;
export type SavedViewCreateWire = InternalRequestInput<'post', '/api/views'>['body'];
export type SavedViewUpdateWire = InternalRequestInput<'put', '/api/views/{pid}'>['body'];
export type SavedViewCopyWire = NonNullable<
  InternalRequestInput<'post', '/api/views/{pid}/copy-to-personal'>['body']
>;
export type SavedViewAutoSaveWire = InternalRequestInput<'post', '/api/views/auto-save'>['body'];
export type SavedViewCapabilityRequestWire = InternalRequestInput<
  'post',
  '/api/views/capability-check'
>['body'];
export type SavedViewCapabilityWire = InternalResponseData<'post', '/api/views/capability-check'>;
export type SavedViewReasonWire = NonNullable<SavedViewCapabilityWire['reasons']>[number];
export type SavedViewAuditWire = InternalResponseData<
  'get',
  '/api/views/{pid}/audit-events'
>[number];
export type SavedViewShareRequestWire = NonNullable<
  InternalRequestInput<'post', '/api/views/{viewPid}/share'>['body']
>;
export type SavedViewShareWire = InternalResponseData<'post', '/api/views/{viewPid}/share'>;
export type SavedViewShareStatusWire = InternalResponseData<
  'get',
  '/api/views/{viewPid}/share/status'
>;
export type SavedViewQueryWire = NonNullable<
  InternalRequestInput<'get', '/api/views/accessible'>['query']
>;
export type SavedViewChipPinWire = InternalResponseData<'get', '/api/views/chip-pins'>[number];
export type SavedViewChipPinRequestWire = NonNullable<
  InternalRequestInput<'post', '/api/views/{viewPid}/pin'>['body']
>;
export type SavedViewTeamWire = InternalResponseData<'get', '/api/views/my-teams'>[number];
export type SavedViewMemberSearchWire = InternalResponseData<'post', '/api/tenant/members/search'>;
export type PublicSharedViewWire = InternalResponseData<'get', '/api/views/shared/{shareToken}'>;

/** A display projection represents nullable optional scalar properties as absent. */
export type PresentFields<T> = { [K in keyof T]: unknown extends T[K] ? T[K] : NonNullable<T[K]> };
