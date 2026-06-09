const NON_DOM_SMART_PROP_NAMES = new Set([
  'autoResize',
  'clearable',
  'context',
  'dataSource',
  'dictCode',
  'expressions',
  'helpText',
  'inline',
  'inputType',
  'multiline',
  'onClear',
  'options',
  'refTarget',
  'showCount',
  'uiSchema',
  'validationRules',
  'visible',
]);

export function sanitizeSmartDomProps<T extends Record<string, unknown>>(props: T): Partial<T> {
  return Object.fromEntries(
    Object.entries(props).filter(([key]) => {
      if (NON_DOM_SMART_PROP_NAMES.has(key)) {
        return false;
      }
      return !key.includes(':');
    }),
  ) as Partial<T>;
}
