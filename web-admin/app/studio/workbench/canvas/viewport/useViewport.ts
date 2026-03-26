/**
 * useViewport Hook
 *
 * Manages canvas zoom, pan, and device preview state.
 *
 * @since 3.2.0
 */

import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import type { ViewportState, UseViewportResult } from './types';
import { ZOOM_MIN, ZOOM_MAX, ZOOM_STEP, getDeviceById } from './devices';

const DEFAULT_STATE: ViewportState = {
  zoom: 1,
  pan: { x: 0, y: 0 },
  deviceWidth: null,
  deviceName: null,
};

interface UseViewportOptions {
  /** Initial zoom level */
  initialZoom?: number;
  /** Initial device ID */
  initialDevice?: string;
  /** Container element ref for calculating fit */
  containerRef?: React.RefObject<HTMLElement>;
}

/**
 * Canvas viewport management hook
 */
export function useViewport(options: UseViewportOptions = {}): UseViewportResult {
  const { initialZoom = 1, initialDevice, containerRef } = options;

  const [state, setState] = useState<ViewportState>(() => {
    const device = initialDevice ? getDeviceById(initialDevice) : null;
    return {
      ...DEFAULT_STATE,
      zoom: initialZoom,
      deviceWidth: device?.width || null,
      deviceName: device?.name || null,
    };
  });

  const [isPanning, setIsPanning] = useState(false);
  const panStartRef = useRef<{ x: number; y: number } | null>(null);

  // Zoom actions
  const setZoom = useCallback((zoom: number) => {
    setState((prev) => ({
      ...prev,
      zoom: Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom)),
    }));
  }, []);

  const zoomIn = useCallback(() => {
    setState((prev) => ({
      ...prev,
      zoom: Math.min(ZOOM_MAX, prev.zoom + ZOOM_STEP),
    }));
  }, []);

  const zoomOut = useCallback(() => {
    setState((prev) => ({
      ...prev,
      zoom: Math.max(ZOOM_MIN, prev.zoom - ZOOM_STEP),
    }));
  }, []);

  const zoomToFit = useCallback(() => {
    if (!containerRef?.current) {
      setZoom(1);
      return;
    }
    // Calculate fit zoom based on container and content size
    const container = containerRef.current;
    const containerWidth = container.clientWidth;
    const contentWidth = state.deviceWidth || 1200;
    const fitZoom = Math.min(1, (containerWidth - 48) / contentWidth);
    setZoom(Math.max(ZOOM_MIN, fitZoom));
  }, [containerRef, state.deviceWidth, setZoom]);

  const zoomToSelection = useCallback(() => {
    // TODO: Implement zoom to selected component
    setZoom(1);
  }, [setZoom]);

  const resetZoom = useCallback(() => {
    setZoom(1);
  }, [setZoom]);

  // Pan actions
  const setPan = useCallback((pan: { x: number; y: number }) => {
    setState((prev) => ({ ...prev, pan }));
  }, []);

  const panBy = useCallback((delta: { x: number; y: number }) => {
    setState((prev) => ({
      ...prev,
      pan: {
        x: prev.pan.x + delta.x,
        y: prev.pan.y + delta.y,
      },
    }));
  }, []);

  const resetPan = useCallback(() => {
    setPan({ x: 0, y: 0 });
  }, [setPan]);

  // Device actions
  const setDevice = useCallback((deviceId: string | null) => {
    if (!deviceId) {
      setState((prev) => ({
        ...prev,
        deviceWidth: null,
        deviceName: null,
      }));
      return;
    }
    const device = getDeviceById(deviceId);
    if (device) {
      setState((prev) => ({
        ...prev,
        deviceWidth: device.width,
        deviceName: device.name,
      }));
    }
  }, []);

  // Reset all
  const reset = useCallback(() => {
    setState(DEFAULT_STATE);
  }, []);

  // Handle wheel zoom
  useEffect(() => {
    const container = containerRef?.current;
    if (!container) return;

    const handleWheel = (e: WheelEvent) => {
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault();
        const delta = e.deltaY > 0 ? -ZOOM_STEP : ZOOM_STEP;
        setZoom(state.zoom + delta);
      }
    };

    container.addEventListener('wheel', handleWheel, { passive: false });
    return () => container.removeEventListener('wheel', handleWheel);
  }, [containerRef, state.zoom, setZoom]);

  // Handle space key for panning mode
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !isPanning && !isInputElement(e.target as HTMLElement)) {
        e.preventDefault();
        setIsPanning(true);
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space') {
        setIsPanning(false);
        panStartRef.current = null;
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    document.addEventListener('keyup', handleKeyUp);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('keyup', handleKeyUp);
    };
  }, [isPanning]);

  // Handle mouse drag for panning
  useEffect(() => {
    if (!isPanning) return;

    const handleMouseDown = (e: MouseEvent) => {
      if (isPanning) {
        e.preventDefault();
        panStartRef.current = { x: e.clientX - state.pan.x, y: e.clientY - state.pan.y };
      }
    };

    const handleMouseMove = (e: MouseEvent) => {
      if (isPanning && panStartRef.current) {
        setPan({
          x: e.clientX - panStartRef.current.x,
          y: e.clientY - panStartRef.current.y,
        });
      }
    };

    const handleMouseUp = () => {
      panStartRef.current = null;
    };

    document.addEventListener('mousedown', handleMouseDown);
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
    return () => {
      document.removeEventListener('mousedown', handleMouseDown);
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isPanning, state.pan, setPan]);

  // Computed styles
  const transform = useMemo(
    () => `translate(${state.pan.x}px, ${state.pan.y}px) scale(${state.zoom})`,
    [state.pan, state.zoom],
  );

  const containerStyle: React.CSSProperties = useMemo(
    () => ({
      cursor: isPanning ? 'grab' : 'default',
      overflow: 'hidden',
    }),
    [isPanning],
  );

  const canvasStyle: React.CSSProperties = useMemo(
    () => ({
      transform,
      transformOrigin: 'center top',
      width: state.deviceWidth ? `${state.deviceWidth}px` : '100%',
      maxWidth: state.deviceWidth ? `${state.deviceWidth}px` : undefined,
      margin: '0 auto',
      transition: isPanning ? 'none' : 'transform 0.1s ease-out',
    }),
    [transform, state.deviceWidth, isPanning],
  );

  return {
    ...state,
    isPanning,
    transform,
    containerStyle,
    canvasStyle,
    setZoom,
    zoomIn,
    zoomOut,
    zoomToFit,
    zoomToSelection,
    resetZoom,
    setPan,
    panBy,
    resetPan,
    setDevice,
    reset,
  };
}

/**
 * Check if element is an input element
 */
function isInputElement(element: HTMLElement): boolean {
  const tagName = element.tagName.toLowerCase();
  return (
    tagName === 'input' ||
    tagName === 'textarea' ||
    tagName === 'select' ||
    element.isContentEditable
  );
}

export default useViewport;
