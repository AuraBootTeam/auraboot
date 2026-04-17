import { Link } from 'react-router';

export function BootstrapNotReady() {
  return (
    <div data-testid="bootstrap-not-ready" className="flex flex-col items-center justify-center min-h-[60vh] px-4">
      <div className="max-w-md text-center bg-white border border-gray-200 rounded shadow p-8 dark:bg-gray-800 dark:border-gray-700">
        <h1 className="text-2xl font-semibold mb-2 text-gray-900 dark:text-white">System not ready</h1>
        <p className="text-gray-600 dark:text-gray-300 mb-6">Please complete system initialization first.</p>
        <Link to="/setup" className="inline-block px-4 py-2 bg-yellow-600 text-white rounded hover:bg-yellow-700">
          Initialize now
        </Link>
      </div>
    </div>
  );
}
