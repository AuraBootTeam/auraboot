import { Outlet } from 'react-router';
import AuthHeader from '~/auth/AuthHeader';

export default function AuthLayout() {
  return (
    <div className="min-h-screen bg-gray-50 transition-colors duration-200 dark:bg-gray-900">
      <AuthHeader />
      <div className="pt-16">
        <Outlet />
      </div>
    </div>
  );
}
