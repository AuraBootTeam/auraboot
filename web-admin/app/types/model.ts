/**
 * Model Management Type Definitions
 *
 * This file contains all TypeScript type definitions for the Model management feature.
 * It includes DTOs, request/response types, and related interfaces.
 */

import type { VersionStatus } from './status';

// ============================================================================
// Foundational Value Types
// ============================================================================

/**
 * Represents any JSON-serializable value.
 * Used for dynamic field values, version change values, and other places
 * where the low-code platform handles arbitrary user data.
 */
export type JsonValue =
  | string
  | number
  | boolean
  | null
  | JsonValue[]
  | { [key: string]: JsonValue };

/**
 * DSL definition object - represents a Page Designer DSL structure.
 * These are deeply nested JSON configs, so we use Record<string, unknown>.
 */
export type DslDefinition = Record<string, unknown>;

/**
 * Dictionary item returned from verification endpoints.
 */
export interface DictItem {
  code: string;
  label: string;
  value: string;
  [key: string]: unknown;
}

// ============================================================================
// Core Model Types
// ============================================================================

/**
 * Model DTO - Data Transfer Object for Model entities
 */
export interface MetaModelDTO {
  id: number;
  pid: string;
  code: string;
  displayName: string;
  description?: string;
  modelType: ModelType;
  tableName?: string;
  status: ModelStatus;
  version: number;
  isCurrent: boolean;
  namespace: string;
  env: string;
  tenantId: number;
  extension?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
  releaseId?: number;
  releasePid?: string;
  fieldCount?: number;
}

/**
 * Model Type Enum
 */
export type ModelType = 'entity' | 'view' | 'aggregate';

/**
 * Model Status Enum
 */
export type ModelStatus = VersionStatus;

// ============================================================================
// Core Field Types
// ============================================================================

/**
 * Field DTO - Data Transfer Object for Field entities
 */
export interface MetaFieldDTO {
  id: number;
  pid: string;
  code: string;
  dataType: string;
  dataSourceId?: number;
  version: number;
  isCurrent: boolean;
  status: string;
  tenantId: number;
  namespace: string;
  env: string;
  description?: string;
  feature?: {
    required?: boolean;
    unique?: boolean;
    indexed?: boolean;
    [key: string]: unknown;
  };
  required?: boolean;
  extension?: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  releaseId?: number;
  releasePid?: string;
  remark?: string;
}

/**
 * Field Create Request
 */
export interface MetaFieldCreateRequest {
  code: string;
  dataType: string;
  dataSourceId?: number;
  namespace?: string;
  env?: string;
  status?: string;
  feature?: Record<string, unknown>;
  refTarget?: Record<string, unknown>;
  indexHint?: Record<string, unknown>;
  uiSchema?: Record<string, unknown>;
  querySchema?: Record<string, unknown>;
  ruleSchema?: Record<string, unknown>;
  extension?: Record<string, unknown>;
  versionNote?: string;
  modelPid?: string; // 关联的模型PID
  autoPublish?: boolean; // 是否立即发布
}

/**
 * Field Update Request
 */
export interface MetaFieldUpdateRequest {
  dataType?: string;
  dataSourceId?: number;
  status?: string;
  feature?: Record<string, unknown>;
  refTarget?: Record<string, unknown>;
  indexHint?: Record<string, unknown>;
  uiSchema?: Record<string, unknown>;
  querySchema?: Record<string, unknown>;
  ruleSchema?: Record<string, unknown>;
  extension?: Record<string, unknown>;
  versionNote?: string;
}

// ============================================================================
// Request Types
// ============================================================================

/**
 * Model Create Request
 */
export interface MetaModelCreateRequest {
  code: string;
  displayName: string;
  description?: string;
  modelType: ModelType;
  namespace?: string;
  env?: string;
  tenantId?: number;
  extension?: Record<string, unknown>;
  versionNote?: string;
}

/**
 * Model Update Request
 */
export interface MetaModelUpdateRequest {
  displayName?: string;
  description?: string;
  modelType?: ModelType;
  namespace?: string;
  env?: string;
  extension?: Record<string, unknown>;
  versionNote?: string;
}

/**
 * Model Query Parameters
 */
export interface ModelQueryParams {
  keyword?: string;
  modelType?: ModelType | '';
  status?: ModelStatus | '';
  page?: number;
  size?: number;
  sortField?: string;
  sortOrder?: 'asc' | 'desc';
}

// ============================================================================
// Response Types
// ============================================================================

/**
 * Paginated Result
 */
export interface PageResult<T> {
  data: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

/**
 * Model Version
 */
export interface ModelVersion {
  version: number;
  isCurrent: boolean;
  status: ModelStatus;
  versionNote?: string;
  createdAt: string;
  createdBy: string;
  content?: Record<string, unknown>;
}

/**
 * Version Diff
 */
export interface VersionDiff {
  fromVersion: number;
  toVersion: number;
  changes: VersionChange[];
}

/**
 * Version Change
 */
export interface VersionChange {
  field: string;
  oldValue: JsonValue;
  newValue: JsonValue;
  changeType: 'added' | 'modified' | 'removed';
}

// ============================================================================
// Model-Field Binding Types
// ============================================================================

/**
 * Model-Field Binding
 */
export interface ModelFieldBinding {
  id: string;
  pid?: string; // Field PID
  fieldPid?: string; // Field PID (alternative name)
  code?: string; // Field code
  modelCode: string;
  fieldCode: string;
  fieldName?: string;
  displayName?: string;
  dataType: string;
  required: boolean;
  readonly?: boolean;
  visible?: boolean;
  editable?: boolean;
  defaultValue?: JsonValue;
  validationRules?: ValidationRule[];
  dictCode?: string;
  dictName?: string;
  displayOrder: number;
  fieldOrder?: number;
  extension?: Record<string, unknown>;
  remark?: string;
  description?: string;
}

/**
 * Validation Rule
 */
export interface ValidationRule {
  type: 'required' | 'pattern' | 'minLength' | 'maxLength' | 'min' | 'max' | 'custom';
  value?: string | number;
  message: string;
  pattern?: string;
}

/**
 * Field Binding Create Request
 */
export interface FieldBindingCreateRequest {
  modelPid: string;
  fieldCode: string;
  required?: boolean;
  defaultValue?: JsonValue;
  validationRules?: ValidationRule[];
  dictCode?: string;
  displayOrder?: number;
  extension?: Record<string, unknown>;
}

/**
 * Field Binding Update Request
 */
export interface FieldBindingUpdateRequest {
  required?: boolean;
  defaultValue?: JsonValue;
  validationRules?: ValidationRule[];
  dictCode?: string;
  displayOrder?: number;
  extension?: Record<string, unknown>;
}

// ============================================================================
// Permission Types
// ============================================================================

/**
 * Permission
 */
export interface Permission {
  id: string;
  type: PermissionType;
  refCode: string;
  action: PermissionAction;
  displayName: string;
  description?: string;
  createdAt: string;
}

/**
 * Permission Type
 */
export type PermissionType = 'model' | 'page' | 'query' | 'api';

/**
 * Permission Action
 */
export type PermissionAction =
  | 'read'
  | 'create'
  | 'update'
  | 'delete'
  | 'query'
  | 'export'
  | 'import';

/**
 * Permission Reference
 */
export interface PermissionReference {
  id: string;
  permissionId: string;
  referenceType: 'role' | 'user' | 'page';
  referenceId: string;
  referenceName: string;
}

// ============================================================================
// CRUD Template Types
// ============================================================================

/**
 * CRUD Template Configuration
 */
export interface CrudTemplateConfig {
  menuName: string;
  menuParentId?: string;
  menuIcon?: string;
  defaultRoles: string[];
  generateList: boolean;
  generateForm: boolean;
  generateDetail: boolean;
  enableExport: boolean;
  enableImport: boolean;
  listColumns?: string[];
  formFields?: string[];
  detailFields?: string[];
}

/**
 * Template Generation Result
 */
export interface TemplateGenerationResult {
  taskId: string;
  status: TemplateGenerationStatus;
  modelCode: string;
  generatedResources: GeneratedResources;
  accessLinks: AccessLinks;
  errors?: string[];
}

/**
 * Template Generation Status
 */
export type TemplateGenerationStatus = 'pending' | 'processing' | 'completed' | 'failed';

/**
 * Generated Resources
 */
export interface GeneratedResources {
  pages: PageInfo[];
  menus: MenuInfo[];
  permissions: PermissionInfo[];
}

/**
 * Page Info
 */
export interface PageInfo {
  id: string;
  pageName: string;
  pageType: string;
  route: string;
  createdAt: string;
}

/**
 * Menu Info
 */
export interface MenuInfo {
  id: string;
  menuName: string;
  menuPath: string;
  parentId?: string;
  icon?: string;
  displayOrder: number;
}

/**
 * Permission Info (for generated resources)
 */
export interface PermissionInfo {
  id: string;
  permissionCode: string;
  permissionName: string;
  resourceType: string;
  resourceId: string;
}

/**
 * @deprecated Use PermissionInfo instead
 */
export interface PermissionInfo {
  id: string;
  permissionCode: string;
  permissionName: string;
  resourceType: string;
  resourceId: string;
}

/**
 * Access Links
 */
export interface AccessLinks {
  listPage?: string;
  formPage?: string;
  detailPage?: string;
}

/**
 * Template
 */
export interface Template {
  id: string;
  templateName: string;
  templateType: string;
  description?: string;
  previewImage?: string;
}

/**
 * Template Preview
 */
export interface TemplatePreview {
  templateId: string;
  modelCode: string;
  previewContent: Record<string, unknown>;
}

// ============================================================================
// State Types
// ============================================================================

/**
 * Model List State
 */
export interface ModelListState {
  filters: {
    keyword: string;
    modelType: ModelType | '';
    status: ModelStatus | '';
  };
  selectedIds: string[];
  pagination: {
    page: number;
    size: number;
    total: number;
  };
}

/**
 * Model Form State
 */
export interface ModelFormState {
  mode: 'create' | 'edit';
  pid?: string;
  form: {
    code: string;
    displayName: string;
    modelType: ModelType;
    description?: string;
    namespace?: string;
    env?: string;
    extension?: Record<string, unknown>;
    versionNote?: string;
  };
  validation: {
    errors: Record<string, string>;
    touched: Record<string, boolean>;
  };
}

/**
 * Model Detail State
 */
export interface ModelDetailState {
  model: MetaModelDTO | null;
  activeTab: 'basic' | 'fields' | 'permissions' | 'versions' | 'pages';
  loading: boolean;
  error: string | null;
}

// ============================================================================
// Git-First Types
// ============================================================================

/**
 * Git-First Notification
 */
export interface GitFirstNotification {
  type: 'info' | 'warning' | 'success' | 'error';
  title: string;
  message: string;
  releaseId?: number;
  releaseStatus?: string;
}

/**
 * Release Status
 */
export type ReleaseStatus =
  | 'pending'
  | 'generating'
  | 'validated'
  | 'projecting'
  | 'published'
  | 'failed';

// ============================================================================
// Error Types
// ============================================================================

/**
 * API Error
 */
export interface ApiError {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  timestamp: string;
}

/**
 * Validation Error
 */
export interface ValidationError extends Error {
  errors: Array<{
    field: string;
    message: string;
  }>;
}

// ============================================================================
// Service Types
// ============================================================================

/**
 * Model Service Interface
 */
export interface IModelService {
  findByPage(params: ModelQueryParams): Promise<PageResult<MetaModelDTO>>;
  findByPid(pid: string): Promise<MetaModelDTO>;
  create(request: MetaModelCreateRequest): Promise<MetaModelDTO>;
  update(pid: string, request: MetaModelUpdateRequest): Promise<MetaModelDTO>;
  delete(pid: string): Promise<void>;
  checkCodeUnique(code: string, excludePid?: string): Promise<boolean>;
  getVersionHistory(code: string): Promise<ModelVersion[]>;
  compareVersions(code: string, v1: number, v2: number): Promise<VersionDiff>;
  rollbackToVersion(code: string, version: number): Promise<MetaModelDTO>;
  refreshCache(pid: string): Promise<void>;
}

/**
 * Permission Service Interface
 */
export interface IPermissionService {
  getModelPermissions(modelCode: string): Promise<Permission[]>;
  getAllPermissions(): Promise<Record<string, Permission[]>>;
  getRolePermissions(roleId: string): Promise<Permission[]>;
  bindPermissionToRole(roleId: string, permissionId: string): Promise<void>;
  unbindPermissionFromRole(roleId: string, permissionId: string): Promise<void>;
  getPermissionReferences(permissionId: string): Promise<PermissionReference[]>;
}

/**
 * Template Service Interface
 */
export interface ITemplateService {
  generateCrudTemplate(
    modelCode: string,
    config: CrudTemplateConfig,
  ): Promise<TemplateGenerationResult>;
  getGenerationResult(taskId: string): Promise<TemplateGenerationResult>;
  getAvailableTemplates(): Promise<Template[]>;
  previewTemplate(modelCode: string, templateId: string): Promise<TemplatePreview>;

  // Runtime Loop Verification Methods
  generatePageDsl(options: DslGenerationOptions): Promise<{
    listDsl?: DslDefinition;
    formDsl?: DslDefinition;
    detailDsl?: DslDefinition;
  }>;
  generateMenuConfig(options: MenuConfigOptions): Promise<Record<string, unknown>>;
  generatePermissionMapping(options: PermissionMappingOptions): Promise<Record<string, unknown>>;
  verifyRuntimeLoop(
    model: MetaModelDTO,
    fields: ModelFieldBinding[],
  ): Promise<RuntimeVerificationResult>;
  testPageAccess(
    modelCode: string,
    pageType: 'list' | 'form' | 'detail',
  ): Promise<{
    accessible: boolean;
    url: string;
    error?: string;
  }>;
  verifyFieldConfig(
    modelCode: string,
    fieldCode: string,
  ): Promise<{
    applied: boolean;
    config: Record<string, unknown>;
    errors?: string[];
  }>;
  verifyDictDisplay(
    modelCode: string,
    fieldCode: string,
    dictCode: string,
  ): Promise<{
    displayed: boolean;
    dictItems: DictItem[];
    errors?: string[];
  }>;
  verifyPermissionControl(
    modelCode: string,
    permission: string,
  ): Promise<{
    controlled: boolean;
    hasPermission: boolean;
    errors?: string[];
  }>;
}

// ============================================================================
// Runtime Verification Types (Task 13)
// ============================================================================

/**
 * DSL Generation Options
 */
export interface DslGenerationOptions {
  modelCode: string;
  modelName: string;
  fields: ModelFieldBinding[];
  includeList?: boolean;
  includeForm?: boolean;
  includeDetail?: boolean;
}

/**
 * Menu Configuration Options
 */
export interface MenuConfigOptions {
  modelCode: string;
  modelName: string;
  parentMenuId?: string;
  icon?: string;
  displayOrder?: number;
}

/**
 * Permission Mapping Options
 */
export interface PermissionMappingOptions {
  modelCode: string;
  permissions: string[];
  defaultRoles?: string[];
}

/**
 * Runtime Verification Result
 */
export interface RuntimeVerificationResult {
  success: boolean;
  generatedPages: {
    list?: string;
    form?: string;
    detail?: string;
  };
  menuPath?: string;
  permissions: string[];
  errors?: string[];
  warnings?: string[];
}

// ============================================================================
// Utility Types
// ============================================================================

/**
 * Request Options
 */
export interface RequestOptions {
  method?: 'get' | 'post' | 'put' | 'delete' | 'patch';
  headers?: Record<string, string>;
  body?: unknown;
  params?: Record<string, string | number | boolean | undefined>;
  timeout?: number;
}

/**
 * Sort Options
 */
export interface SortOptions {
  field: string;
  order: 'asc' | 'desc';
}

/**
 * Filter Options
 */
export interface FilterOptions {
  field: string;
  operator: 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'like' | 'in' | 'notIn';
  value: JsonValue;
}
