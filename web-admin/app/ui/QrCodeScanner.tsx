import { useState, useRef, useEffect } from 'react';
import { QrCodeIcon, CameraIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useNavigate } from 'react-router';
import { useToast } from '~/contexts/ToastContext';
import { useSmartText } from '~/utils/i18n';

interface QrCodeScannerProps {
  isOpen: boolean;
  onClose: () => void;
  onScan?: (result: string) => void;
}

interface DeviceQrCodeData {
  type: string;
  deviceLoginCode: string;
  deviceCode: string;
  deviceName: string;
  deviceType?: string;
  timestamp: number;
}

export default function QrCodeScanner({ isOpen, onClose, onScan }: QrCodeScannerProps) {
  const navigate = useNavigate();
  const { showErrorToast } = useToast();
  const st = useSmartText();
  const [manualCode, setManualCode] = useState('');
  const [isManualMode, setIsManualMode] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);

  useEffect(() => {
    if (isOpen && !isManualMode) {
      startCamera();
    }
    return () => {
      stopCamera();
    };
  }, [isOpen, isManualMode]);

  const startCamera = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: 'environment', // Prefer the rear camera
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
      });

      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        streamRef.current = stream;
      }
    } catch (error) {
      console.error('Failed to access camera:', error);
      showErrorToast(st('$i18n:qr_scanner.camera_access_failed', 'Unable to access camera. Please check permission settings.'));
      setIsManualMode(true);
    }
  };

  const stopCamera = () => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }
  };

  const parseQrCodeData = (qrText: string): DeviceQrCodeData | null => {
    try {
      // Try to parse the QR code as JSON
      const data = JSON.parse(qrText);

      if (
        data.type === 'device_login' &&
        data.deviceLoginCode &&
        data.deviceCode &&
        data.deviceName
      ) {
        return data as DeviceQrCodeData;
      }

      return null;
    } catch (error) {
      // If not JSON, try to parse as a URL
      try {
        const url = new URL(qrText);
        const params = url.searchParams;

        if (params.get('deviceLoginCode') && params.get('deviceCode') && params.get('deviceName')) {
          return {
            type: 'device_login',
            deviceLoginCode: params.get('deviceLoginCode')!,
            deviceCode: params.get('deviceCode')!,
            deviceName: params.get('deviceName')!,
            deviceType: params.get('deviceType') || undefined,
            timestamp: parseInt(params.get('timestamp') || Date.now().toString()),
          };
        }
      } catch (urlError) {
        // Not a valid URL
      }

      return null;
    }
  };

  const handleManualSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!manualCode.trim()) {
      showErrorToast(st('$i18n:qr_scanner.enter_login_code', 'Please enter the device login code'));
      return;
    }
    onScan?.(manualCode.trim());

    // Try to parse the manually entered code
    const deviceData = parseQrCodeData(manualCode.trim());

    if (deviceData) {
      const params = new URLSearchParams({
        deviceLoginCode: deviceData.deviceLoginCode,
        deviceCode: deviceData.deviceCode,
        deviceName: deviceData.deviceName,
        ...(deviceData.deviceType && { deviceType: deviceData.deviceType }),
        timestamp: deviceData.timestamp.toString(),
      });

      onClose();
      navigate(`/device-login?${params.toString()}`);
    } else {
      // If not full QR data, assume it's a device login code
      const params = new URLSearchParams({
        deviceLoginCode: manualCode.trim(),
        deviceCode: 'unknown',
        deviceName: st('$i18n:qr_scanner.unknown_device', 'Unknown device'),
        timestamp: Date.now().toString(),
      });

      onClose();
      navigate(`/device-login?${params.toString()}`);
    }
  };

  const captureAndAnalyze = () => {
    if (!videoRef.current || !canvasRef.current) return;

    const video = videoRef.current;
    const canvas = canvasRef.current;
    const context = canvas.getContext('2d');

    if (!context) return;

    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    context.drawImage(video, 0, 0);

    // A QR recognition library (e.g. jsQR) should be integrated here.
    // Since no such library is installed, this is a simplified implementation.
    // A real project would install jsQR or a similar library.

    // Simulate QR code recognition
    setTimeout(() => {
      if (!isProcessing) {
        captureAndAnalyze();
      }
    }, 500);
  };

  useEffect(() => {
    if (isOpen && !isManualMode && videoRef.current) {
      const video = videoRef.current;
      const handleLoadedMetadata = () => {
        captureAndAnalyze();
      };

      video.addEventListener('loadedmetadata', handleLoadedMetadata);
      return () => {
        video.removeEventListener('loadedmetadata', handleLoadedMetadata);
      };
    }
  }, [isOpen, isManualMode, isProcessing]);

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 overflow-y-auto">
      <div className="flex min-h-screen items-center justify-center px-4 pt-4 pb-20 text-center sm:block sm:p-0">
        <div
          className="bg-opacity-75 fixed inset-0 bg-gray-500 transition-opacity"
          onClick={onClose}
        ></div>

        <div className="rounded-card bg-panel inline-block transform overflow-hidden px-4 pt-5 pb-4 text-left align-bottom shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6 sm:align-middle">
          <div className="absolute top-0 right-0 pt-4 pr-4">
            <button
              type="button"
              className="rounded-control bg-panel text-text-3 hover:text-text-2 focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:outline-none"
              onClick={onClose}
            >
              <span className="sr-only">{st('$i18n:qr_scanner.close', 'Close')}</span>
              <XMarkIcon className="h-6 w-6" />
            </button>
          </div>

          <div className="sm:flex sm:items-start">
            <div className="rounded-pill mx-auto flex h-12 w-12 flex-shrink-0 items-center justify-center bg-indigo-100 sm:mx-0 sm:h-10 sm:w-10">
              <QrCodeIcon className="h-6 w-6 text-indigo-600" />
            </div>
            <div className="mt-3 flex-1 text-center sm:mt-0 sm:ml-4 sm:text-left">
              <h3 className="text-text text-lg leading-6 font-medium">{st('$i18n:qr_scanner.title', 'Scan device QR code')}</h3>
              <div className="mt-2">
                <p className="text-text-2 text-sm">
                  {st('$i18n:qr_scanner.instruction', 'Point your camera at the QR code shown on the TV device, or enter the device login code manually')}
                </p>
              </div>
            </div>
          </div>

          <div className="mt-5">
            <div className="mb-4 flex justify-center space-x-3">
              <button
                onClick={() => setIsManualMode(false)}
                className={`rounded-control px-4 py-2 text-sm font-medium ${
                  !isManualMode
                    ? 'bg-indigo-600 text-white'
                    : 'bg-hover text-text-2 hover:bg-gray-300'
                }`}
              >
                <CameraIcon className="mr-1 inline h-4 w-4" />
                {st('$i18n:qr_scanner.scan_tab', 'Scan QR code')}
              </button>
              <button
                onClick={() => setIsManualMode(true)}
                className={`rounded-control px-4 py-2 text-sm font-medium ${
                  isManualMode
                    ? 'bg-indigo-600 text-white'
                    : 'bg-hover text-text-2 hover:bg-gray-300'
                }`}
              >
                {st('$i18n:qr_scanner.manual_tab', 'Manual input')}
              </button>
            </div>

            {!isManualMode ? (
              <div className="space-y-4">
                <div
                  className="rounded-card relative overflow-hidden bg-black"
                  style={{ aspectRatio: '16/9' }}
                >
                  <video
                    ref={videoRef}
                    autoPlay
                    playsInline
                    muted
                    className="h-full w-full object-cover"
                  />
                  <canvas ref={canvasRef} className="hidden" />

                  {/* Scan frame */}
                  <div className="absolute inset-0 flex items-center justify-center">
                    <div className="rounded-card flex h-48 w-48 items-center justify-center border-2 border-dashed border-white">
                      <div className="text-center text-white">
                        <QrCodeIcon className="mx-auto mb-2 h-8 w-8" />
                        <p className="text-sm">{st('$i18n:qr_scanner.frame_hint', 'Place the QR code within the frame')}</p>
                      </div>
                    </div>
                  </div>
                </div>

                <p className="text-text-2 text-center text-xs">
                  {st('$i18n:qr_scanner.camera_tip', 'Tip: if the camera is unavailable, switch to manual input mode')}
                </p>
              </div>
            ) : (
              <form onSubmit={handleManualSubmit} className="space-y-4">
                <div>
                  <label htmlFor="manual-code" className="text-text-2 block text-sm font-medium">
                    {st('$i18n:qr_scanner.input_label', 'Device login code or QR code content')}
                  </label>
                  <textarea
                    id="manual-code"
                    rows={4}
                    className="rounded-control border-border-strong mt-1 block w-full shadow-sm focus:border-indigo-500 focus:ring-indigo-500 sm:text-sm"
                    placeholder={st('$i18n:qr_scanner.input_placeholder', 'Enter the device login code or paste the full QR code content...')}
                    value={manualCode}
                    onChange={(e) => setManualCode(e.target.value)}
                  />
                </div>

                <div className="flex justify-end space-x-3">
                  <button
                    type="button"
                    onClick={onClose}
                    className="rounded-control border-border-strong bg-panel text-text-2 hover:bg-subtle border px-4 py-2 text-sm font-medium shadow-sm focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:outline-none"
                  >
                    {st('$i18n:qr_scanner.cancel', 'Cancel')}
                  </button>
                  <button
                    type="submit"
                    className="rounded-control border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:outline-none"
                  >
                    {st('$i18n:qr_scanner.confirm', 'Confirm')}
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
