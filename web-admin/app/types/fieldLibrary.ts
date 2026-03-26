/**
 * Field Library Types
 * Type definitions for field library, usage tracking, and impact analysis
 */

// ============================================================================
// Common Types
// ============================================================================

export interface PageResult<T> {
  data: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

// ============================================================================
// Field DTO
// ============================================================================

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
  feature?: Record<string, any>;
  refTarget?: Record<string, any>;
  indexHint?: Record<string, any>;
  uiSchema?: Record<string, any>;
  querySchema?: Record<string, any>;
  ruleSchema?: Record<string, any>;
  extension?: Record<string, any>;
  fieldOrder?: number;
  required?: boolean;
  visible?: boolean;
  editable?: boolean;
  createdBy?: string;
  updatedBy?: string;
  createdAt: string;
  updatedAt: string;
  remark?: string;
}

// ============================================================================
// Field Search
// ============================================================================

export interface FieldSearchRequest {
  keyword?: string;
  baseType?: string;
  semanticType?: string;
  minUsageCount?: number;
  maxUsageCount?: number;
  systemFieldsOnly?: boolean;
  unusedOnly?: boolean;
  page?: number;
  size?: number;
}

export interface FieldSearchResult {
  records: MetaFieldDTO[];
  total: number;
  current: number;
  size: number;
  pages: number;
  hasPrevious: boolean;
  hasNext: boolean;
}

// ============================================================================
// Field Recommendation
// ============================================================================

export interface FieldRecommendation {
  field: MetaFieldDTO;
  usageCount: number;
  relevanceScore: number;
  recommendationReason: string;
  usedByModels: string[];
}

// ============================================================================
// Field Usage
// ============================================================================

export interface FieldUsageInfo {
  fieldPid: string;
  fieldCode: string;
  totalUsageCount: number;
  modelUsages: ModelUsage[];
  lastUpdated: string;
}

export interface ModelUsage {
  modelPid: string;
  modelCode: string;
  modelName: string;
  bindingId: number;
  aliasCode?: string;
  required: boolean;
  visible: boolean;
  editable: boolean;
  fieldOrder?: number;
  createdAt: string;
}

// ============================================================================
// Binding Configuration
// ============================================================================

export interface BindingConfiguration {
  bindingId: number;
  modelPid: string;
  modelCode: string;
  fieldPid: string;
  fieldCode: string;
  aliasCode?: string;
  required: boolean;
  nullable: boolean;
  readonly: boolean;
  visible: boolean;
  editable: boolean;
  defaultValue?: string;
  dictOverrideCode?: string;
  uiHint?: string;
  validationOverride?: string;
  validationRules?: string;
  displayConfig?: string;
  fieldOrder?: number;
  remarks?: string;
  createdAt: string;
  updatedAt: string;
}

// ============================================================================
// Field Binding Request Types
// ============================================================================

export interface FieldBindingRequest {
  fieldPid: string;
  aliasCode?: string;
  required?: boolean;
  nullable?: boolean;
  readonly?: boolean;
  visible?: boolean;
  editable?: boolean;
  defaultValue?: string;
  dictOverrideCode?: string;
  uiHint?: string;
  validationOverride?: string;
  displayConfig?: string;
  remarks?: string;
}

export interface BatchFieldBindingRequest {
  fieldPids: string[];
  commonConfig?: CommonBindingConfig;
}

export interface CommonBindingConfig {
  required?: boolean;
  nullable?: boolean;
  readonly?: boolean;
  visible?: boolean;
  editable?: boolean;
}

// ============================================================================
// Field Selection Dialog Types
// ============================================================================

export type FieldSelectionMode = 'select' | 'create';

export interface FieldSelectionDialogState {
  isOpen: boolean;
  mode: FieldSelectionMode;
  selectedFields: string[]; // Field PIDs
  searchKeyword: string;
  baseTypeFilter?: string;
  semanticTypeFilter?: string;
}

export interface FilterOptions {
  baseTypes: string[];
  semanticTypes: string[];
}

// ============================================================================
// Field Impact Analysis
// ============================================================================

export interface FieldImpactAnalysis {
  fieldPid: string;
  fieldCode: string;
  impactLevel: 'low' | 'medium' | 'high' | 'critical';
  affectedModels: Array<AffectedModel | string>;
  affectedPages: Array<AffectedPage | string>;
  affectedQueries: AffectedQuery[];
  breakingChanges: BreakingChange[];
  recommendations: string[];
  canSafelyModify: boolean;
  canSafelyDelete: boolean;
  canDelete?: boolean;
  blockingReasons?: string[];
  riskLevel?: 'low' | 'medium' | 'high';
}

export interface AffectedModel {
  modelPid: string;
  modelCode: string;
  modelName: string;
  impactType: 'direct' | 'indirect';
  impactDescription: string;
}

export interface AffectedPage {
  pagePid: string;
  pageCode: string;
  pageName: string;
  usageContext: string;
}

export interface AffectedQuery {
  queryPid: string;
  queryCode: string;
  queryName: string;
  usageType: string;
}

export interface BreakingChange {
  changeType: string;
  severity: 'low' | 'medium' | 'high' | 'critical';
  description: string;
  affectedComponents: string[];
  migrationPath?: string;
}
