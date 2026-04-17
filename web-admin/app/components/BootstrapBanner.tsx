import { Link } from 'react-router';
import type { BootstrapStatus } from '~/services/bootstrapStatus';
import { bootstrapT, describeMissingParts } from '~/services/bootstrapTexts';

interface Props {
  status: BootstrapStatus;
}

export function BootstrapBanner({ status }: Props) {
  if (status.initialized) return null;

  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="bootstrap-banner"
      className="fixed top-0 left-0 right-0 z-[1000] bg-yellow-50 border-b border-yellow-300 px-4 py-2 flex items-center justify-between text-yellow-900 text-sm"
    >
      <div>
        <span className="font-medium mr-2">{bootstrapT('bannerTitle')}</span>
        <span>
          {bootstrapT('bannerDetailPrefix')}
          {describeMissingParts(status.missingParts)}
        </span>
      </div>
      <Link
        to="/setup"
        data-testid="bootstrap-banner-cta"
        className="ml-4 px-3 py-1 bg-yellow-600 text-white rounded hover:bg-yellow-700"
      >
        {bootstrapT('bannerCta')}
      </Link>
    </div>
  );
}
