import { Outlet } from 'react-router';
import Header from '~/routes/Header';

export default function TenantSelectionLayout() {
  return (
    <>
      {/* 复用Header组件，使用简化模式 */}
      <Header
        showSidebar={false}
        showNotifications={false}
        showLanguageSwitch={false}
        simplified={true}
      />

      {/* 主内容区域 */}
      <div className="min-h-screen pt-16">
        <Outlet />
      </div>
    </>
  );
}
