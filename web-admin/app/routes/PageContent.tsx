import { Outlet } from 'react-router';

export default function PageContent() {
  return (
    <main className="flex flex-1 flex-col overflow-y-auto bg-gray-50 dark:bg-gray-900">
      <div className="flex min-h-0 flex-1 flex-col">
        <Outlet />
      </div>
    </main>
  );
}
