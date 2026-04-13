/**
 * useViewport Hook
 *
 * Hook for managing canvas viewport state.
 *
 * @since 3.2.0
 */

import { useState, useCallback, useMemo } from 'react';
import { getDevicePreset, getDefaultPreset } from './presets';
import type { ViewportState, ViewportDimensions, DeviceOrientation, CustomDevice } from './types';

interface UseViewportOptions {
  /** Initial device ID */
  initialDeviceId?: string;
  /** Initial zoom level */
  initialZoom?: number;
  /** Min zoom level */
  minZoom?: number;
  /** Max zoom level */
  maxZoom?: number;
  /** Zoom step */
  zoomStep?: number;
}

interface UseViewportReturn {
  /** Current viewport state */
  state: ViewportState;
  /** Effective dimensions */
  dimensions: ViewportDimensions;
  /** Set device */
  setDevice: (deviceId: string) => void;
  /** Set orientation */
  setOrientation: (orientation: DeviceOrientation) => void;
  /** Set custom device */
  setCustomDevice: (device: CustomDevice) => void;
  /** Zoom in */
  zoomIn: () => void;
  /** Zoom out */
  zoomOut: () => void;
  /** Set zoom level */
  setZoom: (zoom: number) => void;
  /** Reset zoom to 100% */
  resetZoom: () => void;
  /** Fit to container */
  fitToContainer: (containerWidth: number, containerHeight: number) => void;
  /** Pan canvas */
  pan: (deltaX: number, deltaY: number) => void;
  /** Reset pan */
  resetPan: () => void;
  /** Reset all */
  reset: () => void;
}

/**
 * useViewport hook
 */
export function useViewport(options: UseViewportOptions = {}): UseViewportReturn {
  const {
    initialDeviceId,
    initialZoom = 100,
    minZoom = 25,
    maxZoom = 200,
    zoomStep = 25,
  } = options;

  const defaultDevice = getDefaultPreset();

  const [state, setState] = useState<ViewportState>({
    deviceId: initialDeviceId || defaultDevice.id,
    orientation: 'portrait',
    zoom: initialZoom,
    panX: 0,
    panY: 0,
  });

  // Calculate effective dimensions
  const dimensions = useMemo((): ViewportDimensions => {
    const device = getDevicePreset(state.deviceId);
    let width = device?.width || 1920;
    let height = device?.height || 1080;

    // Use custom dimensions if set
    if (state.deviceId === 'custom') {
      width = state.customWidth || width;
      height = state.customHeight || height;
    }

    // Apply orientation
    if (
      (state.orientation === 'landscape' && height > width) ||
      (state.orientation === 'portrait' && width > height)
    ) {
      [width, height] = [height, width];
    }

    // Apply zoom
    const scale = state.zoom / 100;

    return {
      width,
      height,
      scale,
    };
  }, [state]);

  // Set device
  const setDevice = useCallback((deviceId: string) => {
    setState((prev) => ({
      ...prev,
      deviceId,
    }));
  }, []);

  // Set orientation
  const setOrientation = useCallback((orientation: DeviceOrientation) => {
    setState((prev) => ({
      ...prev,
      orientation,
    }));
  }, []);

  // Set custom device
  const setCustomDevice = useCallback((device: CustomDevice) => {
    setState((prev) => ({
      ...prev,
      deviceId: 'custom',
      customWidth: device.width,
      customHeight: device.height,
    }));
  }, []);

  // Zoom in
  const zoomIn = useCallback(() => {
    setState((prev) => ({
      ...prev,
      zoom: Math.min(prev.zoom + zoomStep, maxZoom),
    }));
  }, [zoomStep, maxZoom]);

  // Zoom out
  const zoomOut = useCallback(() => {
    setState((prev) => ({
      ...prev,
      zoom: Math.max(prev.zoom - zoomStep, minZoom),
    }));
  }, [zoomStep, minZoom]);

  // Set zoom
  const setZoom = useCallback(
    (zoom: number) => {
      setState((prev) => ({
        ...prev,
        zoom: Math.max(minZoom, Math.min(zoom, maxZoom)),
      }));
    },
    [minZoom, maxZoom],
  );

  // Reset zoom
  const resetZoom = useCallback(() => {
    setState((prev) => ({
      ...prev,
      zoom: 100,
    }));
  }, []);

  // Fit to container
  const fitToContainer = useCallback(
    (containerWidth: number, containerHeight: number) => {
      const device = getDevicePreset(state.deviceId);
      let width = device?.width || 1920;
      let height = device?.height || 1080;

      if (state.deviceId === 'custom') {
        width = state.customWidth || width;
        height = state.customHeight || height;
      }

      // Apply orientation
      if (
        (state.orientation === 'landscape' && height > width) ||
        (state.orientation === 'portrait' && width > height)
      ) {
        [width, height] = [height, width];
      }

      // Calculate fit zoom with padding
      const padding = 40;
      const availableWidth = containerWidth - padding * 2;
      const availableHeight = containerHeight - padding * 2;

      const scaleX = availableWidth / width;
      const scaleY = availableHeight / height;
      const fitScale = Math.min(scaleX, scaleY);

      // Convert to percentage and clamp
      const fitZoom = Math.max(minZoom, Math.min(Math.floor(fitScale * 100), maxZoom));

      setState((prev) => ({
        ...prev,
        zoom: fitZoom,
        panX: 0,
        panY: 0,
      }));
    },
    [state.deviceId, state.customWidth, state.customHeight, state.orientation, minZoom, maxZoom],
  );

  // Pan
  const pan = useCallback((deltaX: number, deltaY: number) => {
    setState((prev) => ({
      ...prev,
      panX: prev.panX + deltaX,
      panY: prev.panY + deltaY,
    }));
  }, []);

  // Reset pan
  const resetPan = useCallback(() => {
    setState((prev) => ({
      ...prev,
      panX: 0,
      panY: 0,
    }));
  }, []);

  // Reset all
  const reset = useCallback(() => {
    setState({
      deviceId: initialDeviceId || defaultDevice.id,
      orientation: 'portrait',
      zoom: initialZoom,
      panX: 0,
      panY: 0,
    });
  }, [initialDeviceId, defaultDevice.id, initialZoom]);

  return {
    state,
    dimensions,
    setDevice,
    setOrientation,
    setCustomDevice,
    zoomIn,
    zoomOut,
    setZoom,
    resetZoom,
    fitToContainer,
    pan,
    resetPan,
    reset,
  };
}

export default useViewport;
