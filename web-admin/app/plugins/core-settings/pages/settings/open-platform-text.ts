import { useI18n } from '~/contexts/I18nContext';

export function useAuditText() {
  const { t } = useI18n();
  return (key: string, fallback: string) => t(`openPlatform.logs.${key}`, undefined, fallback);
}
