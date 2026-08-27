import { isRouteErrorResponse } from 'react-router';

/**
 * Pure helpers for the root error boundary.
 *
 * Kept free of React so the classification, locale and error-id logic can be
 * unit-tested without rendering the boundary.
 */

export type SupportedErrorLocale = 'zh-CN' | 'en-US' | 'ja-JP' | 'ko-KR';

export type RootErrorKind =
  | 'not-found'
  | 'forbidden'
  | 'client'
  | 'server'
  | 'network'
  | 'unknown';

export interface RootErrorView {
  kind: RootErrorKind;
  status?: number;
  retryable: boolean;
}

const LOCALE_CODES: SupportedErrorLocale[] = ['zh-CN', 'en-US', 'ja-JP', 'ko-KR'];

export const ROOT_ERROR_TEXTS = {
  oops: {
    'zh-CN': '出错了！',
    'en-US': 'Oops!',
    'ja-JP': 'エラーが発生しました！',
    'ko-KR': '오류가 발생했습니다!',
  },
  error: {
    'zh-CN': '错误',
    'en-US': 'Error',
    'ja-JP': 'エラー',
    'ko-KR': '오류',
  },
  unexpected: {
    'zh-CN': '发生了意外错误，请重试。如果问题持续，请复制错误 ID 反馈给管理员。',
    'en-US': 'An unexpected error occurred. Please retry, or copy the error ID and contact an administrator.',
    'ja-JP': '予期しないエラーが発生しました。再試行するか、エラーIDをコピーして管理者にお問い合わせください。',
    'ko-KR': '예기치 않은 오류가 발생했습니다. 다시 시도하거나 오류 ID를 복사하여 관리자에게 문의하세요.',
  },
  notFound: {
    'zh-CN': '你访问的页面不存在或已被移除。',
    'en-US': 'The page you requested does not exist or has been moved.',
    'ja-JP': 'お探しのページは存在しないか、移動されました。',
    'ko-KR': '요청한 페이지가 존재하지 않거나 이동되었습니다.',
  },
  forbidden: {
    'zh-CN': '你没有权限访问该页面，请联系管理员开通权限。',
    'en-US': 'You do not have permission to view this page. Please contact an administrator.',
    'ja-JP': 'このページを表示する権限がありません。管理者にお問い合わせください。',
    'ko-KR': '이 페이지에 접근할 권한이 없습니다. 관리자에게 문의하세요.',
  },
  client: {
    'zh-CN': '请求无法完成，请检查后重试。',
    'en-US': 'The request could not be completed. Please try again.',
    'ja-JP': 'リクエストを完了できませんでした。もう一度お試しください。',
    'ko-KR': '요청을 완료할 수 없습니다. 다시 시도해 주세요.',
  },
  server: {
    'zh-CN': '服务暂时不可用，请稍后重试。',
    'en-US': 'The service is temporarily unavailable. Please try again later.',
    'ja-JP': 'サービスが一時的に利用できません。しばらくしてから再試行してください。',
    'ko-KR': '서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  },
  networkTitle: {
    'zh-CN': '网络连接异常',
    'en-US': 'Network Connection Error',
    'ja-JP': 'ネットワーク接続エラー',
    'ko-KR': '네트워크 연결 오류',
  },
  network: {
    'zh-CN': '请检查网络连接后重试。',
    'en-US': 'Please check your network connection and try again.',
    'ja-JP': 'ネットワーク接続を確認して、もう一度お試しください。',
    'ko-KR': '네트워크 연결을 확인한 후 다시 시도해 주세요.',
  },
  techDetails: {
    'zh-CN': '诊断信息',
    'en-US': 'Diagnostics',
    'ja-JP': '診断情報',
    'ko-KR': '진단 정보',
  },
  errorId: {
    'zh-CN': '错误ID',
    'en-US': 'Error ID',
    'ja-JP': 'エラーID',
    'ko-KR': '오류 ID',
  },
  copy: {
    'zh-CN': '复制',
    'en-US': 'Copy',
    'ja-JP': 'コピー',
    'ko-KR': '복사',
  },
  copied: {
    'zh-CN': '已复制',
    'en-US': 'Copied',
    'ja-JP': 'コピーしました',
    'ko-KR': '복사됨',
  },
  retry: {
    'zh-CN': '重试',
    'en-US': 'Retry',
    'ja-JP': '再試行',
    'ko-KR': '다시 시도',
  },
  backHome: {
    'zh-CN': '返回首页',
    'en-US': 'Back to Home',
    'ja-JP': 'ホームに戻る',
    'ko-KR': '홈으로 돌아가기',
  },
  backPrevious: {
    'zh-CN': '返回上一页',
    'en-US': 'Go Back',
    'ja-JP': '前のページに戻る',
    'ko-KR': '이전 페이지로',
  },
  pageUrl: {
    'zh-CN': '页面地址',
    'en-US': 'Page URL',
    'ja-JP': 'ページURL',
    'ko-KR': '페이지 URL',
  },
  occurredAt: {
    'zh-CN': '发生时间',
    'en-US': 'Occurred at',
    'ja-JP': '発生時刻',
    'ko-KR': '발생 시각',
  },
  errorKind: {
    'zh-CN': '错误类型',
    'en-US': 'Error type',
    'ja-JP': 'エラー種別',
    'ko-KR': '오류 유형',
  },
  errorMessage: {
    'zh-CN': '错误信息',
    'en-US': 'Error message',
    'ja-JP': 'エラーメッセージ',
    'ko-KR': '오류 메시지',
  },
} as const;

export type RootErrorTextKey = keyof typeof ROOT_ERROR_TEXTS;

export function rootT(key: RootErrorTextKey, locale: SupportedErrorLocale): string {
  return ROOT_ERROR_TEXTS[key][locale] ?? ROOT_ERROR_TEXTS[key]['en-US'];
}

/**
 * Prefers the application locale (server-resolved from the locale cookie, or
 * the cookie itself) and otherwise uses the app default. The boundary must
 * render identically on the server and the client, so it deliberately does NOT
 * consult `navigator.language` / `document.cookie` here: the root loader
 * resolves the locale cookie into `rootData.locale`, and when the root loader
 * itself failed there is no request context available to the boundary — the
 * app-wide default (zh-CN, matching the server loader) keeps SSR output stable.
 */
export function resolveErrorLocale(cookieLocale?: string): SupportedErrorLocale {
  const lang = cookieLocale?.trim().toLowerCase();
  if (lang?.startsWith('ja')) return 'ja-JP';
  if (lang?.startsWith('ko')) return 'ko-KR';
  if (lang?.startsWith('en')) return 'en-US';
  return 'zh-CN';
}

/**
 * Classifies the value React Router hands to the root error boundary into a
 * stable kind + retryability so the UI can show an actionable message instead
 * of a single generic "something went wrong" for every failure.
 */
export function resolveRootErrorView(error: unknown): RootErrorView {
  if (isRouteErrorResponse(error)) {
    const status = error.status;
    if (status === 404) return { kind: 'not-found', status, retryable: false };
    if (status === 403) return { kind: 'forbidden', status, retryable: false };
    if (status >= 500) return { kind: 'server', status, retryable: true };
    if (status >= 400) return { kind: 'client', status, retryable: false };
    return { kind: 'unknown', status, retryable: true };
  }

  if (error instanceof Error) {
    const message = error.message || '';
    const code = (error as { code?: unknown }).code;
    const isNetworkFailure =
      code === 'network_error' ||
      /failed to fetch|networkerror|load failed|fetch failed|network request failed|the internet connection appears to be offline/i.test(
        message,
      );
    if (isNetworkFailure) return { kind: 'network', retryable: true };
    return { kind: 'unknown', retryable: true };
  }

  return { kind: 'unknown', retryable: true };
}

export function resolveErrorPresentation(
  view: RootErrorView,
  locale: SupportedErrorLocale,
): { title: string; detail: string } {
  const t = (key: RootErrorTextKey) => rootT(key, locale);
  switch (view.kind) {
    case 'not-found':
      return { title: '404', detail: t('notFound') };
    case 'forbidden':
      return { title: '403', detail: t('forbidden') };
    case 'server':
      return { title: String(view.status ?? 500), detail: t('server') };
    case 'client':
      return { title: String(view.status ?? t('error')), detail: t('client') };
    case 'network':
      return { title: t('networkTitle'), detail: t('network') };
    default:
      return { title: t('oops'), detail: t('unexpected') };
  }
}

/**
 * Correlation prefix embedded into the report message so the error id, kind and
 * status survive the backend's fixed-shape client-error log (which persists
 * `message` but has no dedicated errorId/kind/status columns yet) and become
 * searchable in /ops/errors.
 */
export function formatClientReportMessage(
  errorId: string,
  view: RootErrorView,
  message: string,
): string {
  const statusPart = view.status != null ? ` status=${view.status}` : '';
  return `[${errorId} kind=${view.kind}${statusPart}] ${message}`;
}

/**
 * Short, support-friendly correlation id. Unique per boundary render so a user
 * can paste it back to support and the report sent to /api/client-errors can be
 * matched.
 */
export function generateErrorId(): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const length = 8;
  const bytes = new Uint8Array(length);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  let suffix = '';
  for (let i = 0; i < bytes.length; i += 1) {
    suffix += alphabet[bytes[i] % alphabet.length];
  }
  return `ERR-${suffix}`;
}

export function isSupportedErrorLocale(value: string | undefined): value is SupportedErrorLocale {
  return LOCALE_CODES.includes(value as SupportedErrorLocale);
}
