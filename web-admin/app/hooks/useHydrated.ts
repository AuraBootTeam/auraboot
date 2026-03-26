import { useState, useEffect } from 'react';

/**
 * Hook to detect if the component has been hydrated on the client side.
 * This helps prevent hydration mismatches by ensuring server and client render the same content initially.
 */
export function useHydrated() {
  const [hydrated, setHydrated] = useState(false);

  useEffect(() => {
    setHydrated(true);
  }, []);

  return hydrated;
}
