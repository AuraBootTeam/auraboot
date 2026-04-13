/**
 * CreateBlankCard — "Create Blank Table" card shown as the first item
 * in the Template Center grid.
 *
 * Navigates to /meta/models/new to create a new data model from scratch.
 */

import { useNavigate } from 'react-router';
import { PlusIcon } from '@heroicons/react/24/outline';

export function CreateBlankCard() {
  const navigate = useNavigate();

  return (
    <div
      onClick={() => navigate('/meta/models/new')}
      className="group flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed border-gray-300 bg-white p-8 transition-all duration-200 hover:-translate-y-1 hover:border-blue-400 hover:shadow-lg dark:border-gray-600 dark:bg-gray-800 dark:hover:border-blue-500"
      data-testid="create-blank-card"
    >
      <div className="mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-gray-100 transition-colors group-hover:bg-blue-50 dark:bg-gray-700 dark:group-hover:bg-blue-900/30">
        <PlusIcon className="h-7 w-7 text-gray-400 transition-colors group-hover:text-blue-500 dark:text-gray-500 dark:group-hover:text-blue-400" />
      </div>
      <h3 className="mb-1 font-semibold text-gray-900 dark:text-white">Create Blank Table</h3>
      <p className="text-center text-sm text-gray-500 dark:text-gray-400">
        Start from scratch with a blank data table
      </p>
    </div>
  );
}

export default CreateBlankCard;
