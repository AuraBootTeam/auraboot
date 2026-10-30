import { createContext, useContext, useLayoutEffect } from 'react';

export interface DetailPresentation {
  mode: 'modal';
  footerTarget: HTMLElement | null;
  onClose: () => void;
  setTitle?: (title: string | null) => void;
}
export const DetailPresentationContext = createContext<DetailPresentation | null>(null);
export function useDetailPresentation() {
  return useContext(DetailPresentationContext);
}

/** The loaded record owns the host title; pending/error states clear it. */
export function useDetailPresentationTitle(title: string | null) {
  const setTitle = useDetailPresentation()?.setTitle;
  useLayoutEffect(() => {
    setTitle?.(title);
    return () => setTitle?.(null);
  }, [setTitle, title]);
}
