import messages from './widgetText.i18n.json';

export type WidgetTextKey = keyof typeof messages;

/** Locale maps remain intact through registry metadata and unsaved widget configuration. */
export function widgetText(key: WidgetTextKey): { 'zh-CN': string; 'en-US': string } {
  return messages[key];
}
