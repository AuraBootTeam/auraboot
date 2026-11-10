import { Outlet } from 'react-router';
import AuthHeader from '~/auth/AuthHeader';

export default function AuthLayout() {
  return (
    <div className="auth-layout min-h-screen bg-[#f6f8f3] transition-colors duration-200 dark:bg-gray-900">
      <AuthHeader />
      <div className="auth-layout-content pt-16">
        <Outlet />
      </div>
    </div>
  );
}
