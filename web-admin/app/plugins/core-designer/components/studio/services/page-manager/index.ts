/**
 * Page Manager Module
 *
 * Page management service and types.
 *
 * @since 3.2.0
 */

// Domain Types
export type {
  PageMeta,
  PageStatus,
  PageMode,
  PageListFilter,
  PageListSort,
  PageListPagination,
  PageListResult,
  CreatePageRequest,
  UpdatePageRequest,
  PageTemplate,
} from './types';

export { PAGE_MODE_INFO, PAGE_STATUS_INFO } from './types';

// API Types (wire format)
export type {
  PageSchemaDTO,
  PageSchemaCreateRequest,
  PageSchemaUpdateRequest,
  PageSchemaVersionDTO,
  PageSchemaVersionComparisonDTO,
  PaginationResult,
  ApiPageType,
  ApiPageStatus,
  ApiResponse,
} from './api-types';

// API Functions
export * as pageApi from './pageApi';

// Type Converters
export {
  toPageMeta,
  toApiPageType,
  fromApiPageType,
  toApiPageStatus,
  fromApiPageStatus,
  toCreateRequest,
  toUpdateRequest,
} from './converters';

// Service
export { PageManagerService, pageManagerService } from './PageManagerService';
