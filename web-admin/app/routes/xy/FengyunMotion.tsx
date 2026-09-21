import React, { useEffect, useRef, useState } from 'react';

type MotionPlayer = {
  play(name?: string): MotionPlayer;
  pause(): MotionPlayer;
  resume(): MotionPlayer;
  destroy(): void;
};

type MotionRuntime = {
  createPlayer(
    canvas: HTMLCanvasElement,
    packet: unknown,
    options: {
      requestFrame: (callback: FrameRequestCallback) => number;
      cancelFrame: (id: number) => void;
      onComplete: () => void;
      onError: () => void;
    },
  ): MotionPlayer;
};

declare global {
  interface Window {
    FengyunMotion?: MotionRuntime;
  }
}

let runtimePromise: Promise<MotionRuntime> | null = null;

function loadRuntime(): Promise<MotionRuntime> {
  if (typeof window === 'undefined') return Promise.reject(new Error('browser runtime required'));
  if (window.FengyunMotion) return Promise.resolve(window.FengyunMotion);
  if (runtimePromise) return runtimePromise;
  const loading = new Promise<MotionRuntime>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = '/static/xiaoya/motion/engine.js';
    script.async = true;
    script.onload = () =>
      window.FengyunMotion
        ? resolve(window.FengyunMotion)
        : reject(new Error('motion runtime missing'));
    script.onerror = () => reject(new Error('motion runtime failed to load'));
    document.head.appendChild(script);
  }).catch((error: unknown) => {
    runtimePromise = null;
    throw error;
  });
  runtimePromise = loading;
  return loading;
}

export function motionPacketForAsset(assetUrl: string | null): string | null {
  if (!assetUrl) return null;
  const bee = assetUrl.match(/\/static\/xiaoya\/themes\/([^/]+)\/(stage_[1-5])\.svg$/);
  if (bee) return `/static/xiaoya/motion/bees/${bee[1]}/${bee[2]}/animation.json`;
  const home = assetUrl.match(/\/static\/xiaoya\/home\/(stage_[1-5])\.svg$/);
  if (home) return `/static/xiaoya/motion/home/${home[1]}/animation.json`;
  const tree = assetUrl.match(/\/static\/xiaoya\/bee-tree-(stage-[1-5])\.svg$/);
  if (tree) return `/static/xiaoya/motion/tree/${tree[1].replace('-', '_')}/animation.json`;
  return null;
}

export function FengyunMotionArtwork({
  assetUrl,
  alt,
  size,
  idleClip = 'idle',
  clickClip,
  className = '',
}: {
  assetUrl: string;
  alt: string;
  size: number;
  idleClip?: string;
  clickClip?: string;
  className?: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const playerRef = useRef<MotionPlayer | null>(null);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const packetUrl = motionPacketForAsset(assetUrl);

  useEffect(() => {
    let cancelled = false;
    let onVisibility: (() => void) | null = null;
    setReady(false);
    setFailed(false);
    if (!packetUrl || !canvasRef.current) {
      setFailed(true);
      return undefined;
    }
    const canvas = canvasRef.current;
    const ratio = Math.min(window.devicePixelRatio || 1, 3);
    canvas.width = Math.round(size * ratio);
    canvas.height = Math.round(size * ratio);
    Promise.all([
      loadRuntime(),
      fetch(packetUrl, { credentials: 'same-origin' }).then((response) => {
        if (!response.ok) throw new Error(`motion packet HTTP ${response.status}`);
        return response.json();
      }),
    ])
      .then(([runtime, packet]) => {
        if (cancelled) return;
        const player = runtime.createPlayer(canvas, packet, {
          requestFrame: (callback) => window.requestAnimationFrame(callback),
          cancelFrame: (id) => window.cancelAnimationFrame(id),
          onComplete: () => player.play(idleClip),
          onError: () => setFailed(true),
        });
        playerRef.current = player;
        player.play(idleClip);
        onVisibility = () => (document.hidden ? player.pause() : player.resume());
        document.addEventListener('visibilitychange', onVisibility);
        setReady(true);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      if (onVisibility) document.removeEventListener('visibilitychange', onVisibility);
      playerRef.current?.destroy();
      playerRef.current = null;
    };
  }, [assetUrl, idleClip, packetUrl, size]);

  const fallback = failed || !packetUrl;
  return (
    <div
      className={`relative ${className}`}
      style={{ width: size, height: size }}
      data-motion-packet={packetUrl || 'none'}
    >
      {!fallback && (
        <canvas
          ref={canvasRef}
          className="h-full w-full object-contain"
          role="img"
          aria-label={alt}
          data-testid="fengyun-motion-canvas"
          onClick={clickClip ? () => playerRef.current?.play(clickClip) : undefined}
          style={{ cursor: clickClip ? 'pointer' : 'default', opacity: ready ? 1 : 0 }}
        />
      )}
      {(!ready || fallback) && (
        <img src={assetUrl} alt={alt} className="absolute inset-0 h-full w-full object-contain" />
      )}
    </div>
  );
}
