import { useSmartText } from '~/utils/i18n';

export function DynamicPageUnavailable(_props: { message: string }) {
  const text = useSmartText();
  // This component is used only for explicit menu-access denials. Keep server
  // diagnostics out of the user-facing message, including internal route keys.
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div className="max-w-md rounded-lg bg-white p-6 shadow-md">
        <div className="mb-4 flex items-center gap-3">
          <svg
            className="h-6 w-6 text-amber-500"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
            />
          </svg>
          <h2 className="text-lg font-semibold text-gray-900">
            {text({ 'zh-CN': '页面不可用', en: 'Page Unavailable' })}
          </h2>
        </div>
        <p className="text-gray-600">
          {text({
            'zh-CN': '无权限访问此页面，请联系管理员。',
            en: 'Access denied. Contact your administrator to request access.',
          })}
        </p>
      </div>
    </div>
  );
}
