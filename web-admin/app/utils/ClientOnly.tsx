/**
 * ClientOnly — render children only after hydration on the client.
 *
 * Avoids hydration mismatches when the rendered tree depends on browser-only
 * APIs (window, localStorage, document). Server renders the fallback (or
 * nothing) and the real children mount after the first useEffect tick.
 */
import { useEffect, useState, type ReactElement, type ReactNode } from 'react';

interface ClientOnlyProps {
  children: ReactNode;
  fallback?: ReactNode;
}

export function ClientOnly({ children, fallback = null }: ClientOnlyProps): ReactElement {
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    setMounted(true);
  }, []);
  return <>{mounted ? children : fallback}</>;
}

export default ClientOnly;
