export interface FieldMetaInfo {
  dataType: string;
  displayName?: string;
  dictCode?: string;
  component?: string;
  refTarget?: Record<string, any>;
  referenceModelCode?: string;
  required?: boolean;
  feature?: Record<string, any>;
  ruleSchema?: Record<string, any>;
  extension?: Record<string, any>;
  extensionProps?: Record<string, any>;
}

export interface NormalizedValidationRule {
  type: string;
  message?: string;
  maxLength?: number;
  minLength?: number;
  minValue?: number;
  maxValue?: number;
  pattern?: string;
}

export interface FormValidationResult {
  fieldErrors: Record<string, string>;
  summaryErrors: string[];
}

export interface BomUploadReviewValueLike {
  file: File;
  valid: boolean;
  validationMessage?: string;
  payload: {
    bom_sheet_name: string;
    bom_header_row_index: number;
    bom_selected_columns: Array<{
      index: number;
      header: string;
      role: string;
    }>;
  };
}
