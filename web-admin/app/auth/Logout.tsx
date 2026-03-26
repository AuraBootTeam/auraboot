import { logout } from '~/services/session';
import { type ActionFunctionArgs, type LoaderFunctionArgs, Form, Link } from 'react-router';

export const loader = async (_args: LoaderFunctionArgs) => {
  return {};
};

export const action = async ({ request }: ActionFunctionArgs) => {
  return logout(request);
};

export default function Screen() {
  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-gradient-to-br from-gray-50 via-blue-50 to-indigo-50 p-4 dark:from-gray-900 dark:via-gray-800 dark:to-gray-900">
      {/* Decorative background */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -top-40 -right-40 h-80 w-80 rounded-full bg-gradient-to-br from-blue-400/20 to-indigo-600/20 blur-3xl"></div>
        <div className="absolute -bottom-40 -left-40 h-80 w-80 rounded-full bg-gradient-to-tr from-purple-400/20 to-pink-600/20 blur-3xl"></div>
      </div>

      <div className="relative z-10 w-full max-w-md">
        <div className="rounded-3xl border border-white/20 bg-white/95 p-8 shadow-2xl backdrop-blur-sm lg:p-10 dark:border-gray-700/50 dark:bg-gray-800/95">
          {/* Icon */}
          <div className="mb-8 text-center">
            <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-gradient-to-r from-orange-400 to-red-500 shadow-lg">
              <svg
                className="h-8 w-8 text-white"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"
                />
              </svg>
            </div>
            <h1 className="mb-2 text-3xl font-bold text-gray-900 dark:text-white">退出登录</h1>
            <p className="text-gray-600 dark:text-gray-400">确定要退出当前账号吗？</p>
          </div>

          <Form method="post" className="space-y-4">
            <button
              type="submit"
              className="w-full transform rounded-xl bg-gradient-to-r from-orange-500 to-red-500 px-6 py-3 text-lg font-semibold text-white shadow-lg transition-all duration-200 hover:-translate-y-0.5 hover:from-orange-600 hover:to-red-600 hover:shadow-xl focus:ring-4 focus:ring-red-300 focus:outline-none dark:focus:ring-red-800"
            >
              确认退出
            </button>
          </Form>

          <div className="mt-6 text-center">
            <Link
              to="/"
              className="font-medium text-gray-600 transition-colors duration-200 hover:text-gray-900 dark:text-gray-400 dark:hover:text-white"
            >
              返回首页
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
