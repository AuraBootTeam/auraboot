// Pre-bootstrap UI text lookup. The i18n DB is empty until bootstrap completes,
// so banner / not-ready / already-done components cannot use the runtime i18n
// pipeline. Mirrors the ROOT_ERROR_TEXTS pattern in root.tsx.

type SupportedLocale = 'zh-CN' | 'en-US' | 'ja-JP' | 'ko-KR';

const TEXTS = {
  bannerTitle: {
    'zh-CN': '系统未完成初始化',
    'en-US': 'System not initialized',
    'ja-JP': 'システムが初期化されていません',
    'ko-KR': '시스템이 초기화되지 않았습니다',
  },
  bannerDetailPrefix: {
    'zh-CN': '缺少：',
    'en-US': 'Missing: ',
    'ja-JP': '不足：',
    'ko-KR': '누락: ',
  },
  bannerCta: {
    'zh-CN': '前往初始化',
    'en-US': 'Initialize now',
    'ja-JP': '初期化する',
    'ko-KR': '초기화 진행',
  },
  notReadyTitle: {
    'zh-CN': '系统未就绪',
    'en-US': 'System not ready',
    'ja-JP': 'システム準備中',
    'ko-KR': '시스템 준비 안 됨',
  },
  notReadyBody: {
    'zh-CN': '请先完成系统初始化后再使用此功能。',
    'en-US': 'Please complete system initialization first.',
    'ja-JP': '先にシステムの初期化を完了してください。',
    'ko-KR': '먼저 시스템 초기화를 완료하세요.',
  },
  notReadyCta: {
    'zh-CN': '前往初始化',
    'en-US': 'Initialize now',
    'ja-JP': '初期化する',
    'ko-KR': '초기화 진행',
  },
  alreadyDoneTitle: {
    'zh-CN': '系统已初始化',
    'en-US': 'System already initialized',
    'ja-JP': 'システムは初期化済みです',
    'ko-KR': '시스템이 이미 초기화됨',
  },
  alreadyDoneBody: {
    'zh-CN': '无需重复操作。',
    'en-US': 'No further action needed.',
    'ja-JP': '追加の操作は不要です。',
    'ko-KR': '추가 작업이 필요 없습니다.',
  },
  alreadyDoneCta: {
    'zh-CN': '返回首页',
    'en-US': 'Back to home',
    'ja-JP': 'ホームに戻る',
    'ko-KR': '홈으로 돌아가기',
  },
} as const;

const MISSING_PART_LABELS: Record<string, Record<SupportedLocale, string>> = {
  admin_user: {
    'zh-CN': '管理员账户',
    'en-US': 'Admin account',
    'ja-JP': '管理者アカウント',
    'ko-KR': '관리자 계정',
  },
  default_tenant: {
    'zh-CN': '默认租户',
    'en-US': 'Default tenant',
    'ja-JP': 'デフォルトテナント',
    'ko-KR': '기본 테넌트',
  },
  system_config: {
    'zh-CN': '系统配置标记',
    'en-US': 'System config flag',
    'ja-JP': 'システム設定フラグ',
    'ko-KR': '시스템 설정 플래그',
  },
};

const PART_SEPARATOR: Record<SupportedLocale, string> = {
  'zh-CN': '、',
  'en-US': ', ',
  'ja-JP': '、',
  'ko-KR': ', ',
};

type TextKey = keyof typeof TEXTS;

function detectLocale(): SupportedLocale {
  if (typeof navigator === 'undefined') return 'en-US';
  const navLang = navigator.language;
  if (navLang?.startsWith('zh')) return 'zh-CN';
  if (navLang?.startsWith('ja')) return 'ja-JP';
  if (navLang?.startsWith('ko')) return 'ko-KR';
  return 'en-US';
}

export function bootstrapT(key: TextKey, locale?: SupportedLocale): string {
  const lang = locale ?? detectLocale();
  return TEXTS[key][lang];
}

export function describeMissingParts(parts: string[], locale?: SupportedLocale): string {
  if (!parts.length) return '';
  const lang = locale ?? detectLocale();
  return parts.map((p) => MISSING_PART_LABELS[p]?.[lang] ?? p).join(PART_SEPARATOR[lang]);
}
