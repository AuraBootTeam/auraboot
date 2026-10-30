import { createContext, useContext } from 'react';

/** UI source invalidation only; never a record, permission, query or command identity. */
export const DetailSourceRefreshContext = createContext(0);

/** Omission preserves existing chart lifecycle behavior. */
export function useDetailSourceRefreshVersion(): number {
  return useContext(DetailSourceRefreshContext);
}
