export function buildRequiredFieldMessage(
  label: string,
  options?: {
    dataType?: string;
    component?: string;
    locale?: string;
    t?: (key: string) => string;
  },
): string {
  const normalizedLabel = String(label || '').trim() || 'This field';
  const normalizedDataType = String(options?.dataType || '').toLowerCase();
  const normalizedComponent = String(options?.component || '').toLowerCase();
  const locale = String(options?.locale || 'en-US');
  const t = options?.t;

  if (!locale.toLowerCase().startsWith('zh')) {
    const requiredMsg = t?.('common.validation.required');
    return `${normalizedLabel} ${
      requiredMsg && requiredMsg !== 'common.validation.required' ? requiredMsg : 'is required'
    }`;
  }

  const selectLikeComponents = new Set([
    'smartselect',
    'select',
    'memberpicker',
    'userselect',
    'organizationselect',
    'treeselect',
    'cascadeselect',
    'radio',
    'checkbox',
    'smartdatepicker',
    'datepicker',
    'date',
    'daterange',
  ]);

  if (normalizedDataType === 'file' || normalizedComponent === 'upload' || normalizedComponent === 'smartupload') {
    return `请上传${normalizedLabel}`;
  }

  if (
    selectLikeComponents.has(normalizedComponent) ||
    ['enum', 'reference', 'date', 'datetime', 'boolean'].includes(normalizedDataType)
  ) {
    return `请选择${normalizedLabel}`;
  }

  return `请填写${normalizedLabel}`;
}
