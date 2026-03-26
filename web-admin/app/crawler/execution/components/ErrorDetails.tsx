import { useState } from 'react';

interface ErrorDetailsProps {
  error: {
    message: string;
    stackTrace?: string;
    failedUrls?: string[];
  };
}

export default function ErrorDetails({ error }: ErrorDetailsProps) {
  const [showStackTrace, setShowStackTrace] = useState(false);
  const [showFailedUrls, setShowFailedUrls] = useState(false);

  return (
    <div className="space-y-4">
      {/* Error Message */}
      <div className="alert alert-error">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-6 w-6 shrink-0 stroke-current"
          fill="none"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
            d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
        <span className="font-semibold">{error.message}</span>
      </div>

      {/* Stack Trace */}
      {error.stackTrace && (
        <div className="collapse-arrow bg-base-200 collapse">
          <input
            type="checkbox"
            checked={showStackTrace}
            onChange={() => setShowStackTrace(!showStackTrace)}
          />
          <div className="collapse-title font-medium">堆栈跟踪</div>
          <div className="collapse-content">
            <pre className="bg-base-300 overflow-x-auto rounded-lg p-4 text-xs">
              {error.stackTrace}
            </pre>
          </div>
        </div>
      )}

      {/* Failed URLs */}
      {error.failedUrls && error.failedUrls.length > 0 && (
        <div className="collapse-arrow bg-base-200 collapse">
          <input
            type="checkbox"
            checked={showFailedUrls}
            onChange={() => setShowFailedUrls(!showFailedUrls)}
          />
          <div className="collapse-title font-medium">失败的 URL ({error.failedUrls.length})</div>
          <div className="collapse-content">
            <ul className="space-y-2">
              {error.failedUrls.map((url, index) => (
                <li key={index} className="flex items-start gap-2">
                  <span className="text-error">•</span>
                  <a
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="link link-hover text-sm break-all"
                  >
                    {url}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </div>
  );
}
