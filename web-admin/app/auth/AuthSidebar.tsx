import React from 'react';
import { Link, useLocation } from 'react-router';
import { QrCodeIcon, XMarkIcon } from '@heroicons/react/24/outline';

interface AuthSidebarProps {
  sidebarOpen: boolean;
  setSidebarOpen: (open: boolean) => void;
}

const authRoutes = [
  {
    path: '/h5-scan',
    icon: <QrCodeIcon className="h-5 w-5" />,
    name: '扫码登录',
  },
];

export default function AuthSidebar({ sidebarOpen, setSidebarOpen }: AuthSidebarProps) {
  const location = useLocation();

  return (
    <>
      {/* 移动端遮罩层 */}
      {sidebarOpen && (
        <div
          className="bg-opacity-50 fixed inset-0 z-40 bg-black lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* 侧边栏 */}
      <div
        className={`fixed top-0 left-0 z-50 h-full w-64 transform bg-white shadow-xl transition-transform duration-300 ease-in-out lg:hidden dark:bg-gray-800 ${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        {/* 侧边栏头部 */}
        <div className="flex items-center justify-between border-b border-gray-200 p-4 dark:border-gray-700">
          <div className="flex items-center">
            <img className="h-8 w-8 rounded-lg" src="/logo192.png" alt="Logo" />
            <span className="ml-3 text-lg font-bold text-gray-900 dark:text-white">AuraBoot</span>
          </div>
          <button
            onClick={() => setSidebarOpen(false)}
            className="rounded-lg p-2 text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-gray-200"
          >
            <XMarkIcon className="h-6 w-6" />
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="mt-6 px-4">
          <div className="space-y-2">
            {authRoutes.map((route) => {
              const isActive = location.pathname === route.path;
              return (
                <Link
                  key={route.path}
                  to={route.path}
                  onClick={() => setSidebarOpen(false)}
                  className={`flex items-center rounded-lg px-4 py-3 text-sm font-medium transition-colors ${
                    isActive
                      ? 'border-r-2 border-blue-600 bg-blue-50 text-blue-600 dark:border-blue-400 dark:bg-blue-900/20 dark:text-blue-400'
                      : 'text-gray-700 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-300 dark:hover:bg-gray-700 dark:hover:text-white'
                  }`}
                >
                  <span className="mr-3">{route.icon}</span>
                  {route.name}
                </Link>
              );
            })}
          </div>
        </nav>

        {/* 底部信息 */}
        <div className="absolute right-0 bottom-0 left-0 border-t border-gray-200 p-4 dark:border-gray-700">
          <div className="text-center text-xs text-gray-500 dark:text-gray-400">
            <p>© {new Date().getFullYear()} AuraBoot</p>
            <p className="mt-1">认证页面导航</p>
          </div>
        </div>
      </div>
    </>
  );
}
