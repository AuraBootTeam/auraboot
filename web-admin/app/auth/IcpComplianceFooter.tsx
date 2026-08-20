import { ICP_RECORD_LOOKUP_URL } from '~/config/icpCompliance';
import { useRootLoaderData } from '~/root-data';

export default function IcpComplianceFooter() {
  const compliance = useRootLoaderData()!.icpCompliance;

  if (!compliance.enabled) return null;

  return (
    <footer
      data-testid="icp-compliance-footer"
      className="flex min-h-11 items-center justify-center border-t border-gray-200 bg-white px-4 py-3 text-center text-xs text-gray-500 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-400"
    >
      <a
        data-testid="icp-record-link"
        href={ICP_RECORD_LOOKUP_URL}
        target="_blank"
        rel="noopener noreferrer"
        className="transition-colors hover:text-[#4B3FE4] hover:underline dark:hover:text-[#a99dff]"
      >
        {compliance.recordNumber}
      </a>
    </footer>
  );
}
