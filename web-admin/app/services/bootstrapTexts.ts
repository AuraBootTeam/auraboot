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
  bannerBody: {
    'zh-CN': '请先完成初始化向导。',
    'en-US': 'Complete the initialization wizard to continue.',
    'ja-JP': '初期化ウィザードを完了してください。',
    'ko-KR': '초기화 마법사를 완료하세요.',
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
