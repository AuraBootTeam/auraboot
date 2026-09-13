import { useEffect, useRef } from 'react';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { reportClientError } from '~/shared/observability/clientErrorReporter';

/** Reports visible presentation once per mounted result; server IDs deduplicate remounts. */
export function useAnalyticsResultView(analysisId?: string) {
  const element = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!analysisId || !element.current) return;
    let attempted = false;
    let intersecting = false;
    const record = async () => {
      if (attempted || !intersecting || document.visibilityState !== 'visible') return;
      attempted = true;
      try {
        const result = await post(
          `/api/analytics/results/${encodeURIComponent(analysisId)}/view`,
          {},
        );
        if (!ResultHelper.isSuccess(result))
          throw new Error('Result presentation was not recorded');
      } catch {
        // Telemetry failure is observable but must not prevent reading or saving the result.
        reportClientError({
          errorType: 'analytics-presentation',
          kind: 'telemetry',
          message: 'Result presentation was not recorded',
          pageUrl: window.location.pathname,
        });
      }
    };
    const observer = new IntersectionObserver((entries) => {
      intersecting = entries.some((entry) => entry.isIntersecting);
      void record();
    });
    observer.observe(element.current);
    document.addEventListener('visibilitychange', record);
    return () => {
      observer.disconnect();
      document.removeEventListener('visibilitychange', record);
    };
  }, [analysisId]);
  return element;
}
