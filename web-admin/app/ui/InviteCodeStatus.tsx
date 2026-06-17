import { CheckIcon, PlusIcon } from '@heroicons/react/24/outline';

interface InviteCodeStatusProps {
  currentInviteCode: any;
  onGenerateClick: () => void;
  onManageClick: () => void;
}

export default function InviteCodeStatus({
  currentInviteCode,
  onGenerateClick,
  onManageClick,
}: InviteCodeStatusProps) {
  return (
    <div className="flex items-center space-x-3">
      {currentInviteCode ? (
        // 当存在邀请码时，显示邀请码管理按钮
        <>
          <button
            onClick={onManageClick}
            className="rounded-control inline-flex items-center border border-transparent bg-green-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-green-700 focus:ring-2 focus:ring-green-500 focus:ring-offset-2 focus:outline-none"
          >
            <CheckIcon className="mr-2 -ml-1 h-5 w-5" aria-hidden="true" />
            邀请码管理
          </button>
        </>
      ) : (
        // 当不存在邀请码时，显示邀请新成员按钮
        <button
          onClick={onGenerateClick}
          className="rounded-control bg-accent hover:bg-accent-hover focus-visible:shadow-focus inline-flex items-center border border-transparent px-4 py-2 text-sm font-medium text-white shadow-sm focus:outline-none"
        >
          <PlusIcon className="mr-2 -ml-1 h-5 w-5" aria-hidden="true" />
          邀请新成员
        </button>
      )}
    </div>
  );
}
