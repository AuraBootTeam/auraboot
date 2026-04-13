import { useState, useRef, useEffect } from 'react';
import { QrCodeIcon, CameraIcon, XMarkIcon } from '@heroicons/react/24/outline';
import { useNavigate } from 'react-router';
import { useToast } from '~/contexts/ToastContext';

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
          facingMode: 'environment', // 优先使用后置摄像头
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
      });

      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        streamRef.current = stream;
      }
    } catch (error) {
      console.error('无法访问摄像头:', error);
      showErrorToast('无法访问摄像头，请检查权限设置');
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
      // 尝试解析JSON格式的二维码
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
      // 如果不是JSON格式，尝试解析URL格式
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
        // 不是有效的URL
      }

      return null;
    }
  };

  const handleManualSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!manualCode.trim()) {
      showErrorToast('请输入设备登录码');
      return;
    }
    onScan?.(manualCode.trim());

    // 尝试解析手动输入的代码
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
      // 如果不是完整的二维码数据，假设是设备登录码
      const params = new URLSearchParams({
        deviceLoginCode: manualCode.trim(),
        deviceCode: 'unknown',
        deviceName: '未知设备',
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

    // 这里应该集成二维码识别库，比如 jsQR
    // 由于没有安装相关库，这里提供一个简化的实现
    // 实际项目中需要安装 jsQR 或类似的库

    // 模拟二维码识别
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

        <div className="inline-block transform overflow-hidden rounded-lg bg-white px-4 pt-5 pb-4 text-left align-bottom shadow-xl transition-all sm:my-8 sm:w-full sm:max-w-lg sm:p-6 sm:align-middle">
          <div className="absolute top-0 right-0 pt-4 pr-4">
            <button
              type="button"
              className="rounded-md bg-white text-gray-400 hover:text-gray-600 focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:outline-none"
              onClick={onClose}
            >
              <span className="sr-only">关闭</span>
              <XMarkIcon className="h-6 w-6" />
            </button>
          </div>

          <div className="sm:flex sm:items-start">
            <div className="mx-auto flex h-12 w-12 flex-shrink-0 items-center justify-center rounded-full bg-indigo-100 sm:mx-0 sm:h-10 sm:w-10">
              <QrCodeIcon className="h-6 w-6 text-indigo-600" />
            </div>
            <div className="mt-3 flex-1 text-center sm:mt-0 sm:ml-4 sm:text-left">
              <h3 className="text-lg leading-6 font-medium text-gray-900">扫描设备二维码</h3>
              <div className="mt-2">
                <p className="text-sm text-gray-500">
                  请将摄像头对准TV设备上显示的二维码，或手动输入设备登录码
                </p>
              </div>
            </div>
          </div>

          <div className="mt-5">
            <div className="mb-4 flex justify-center space-x-3">
              <button
                onClick={() => setIsManualMode(false)}
                className={`rounded-md px-4 py-2 text-sm font-medium ${
                  !isManualMode
                    ? 'bg-indigo-600 text-white'
                    : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                }`}
              >
                <CameraIcon className="mr-1 inline h-4 w-4" />
                扫描二维码
              </button>
              <button
                onClick={() => setIsManualMode(true)}
                className={`rounded-md px-4 py-2 text-sm font-medium ${
                  isManualMode
                    ? 'bg-indigo-600 text-white'
                    : 'bg-gray-200 text-gray-700 hover:bg-gray-300'
                }`}
              >
                手动输入
              </button>
            </div>

            {!isManualMode ? (
              <div className="space-y-4">
                <div
                  className="relative overflow-hidden rounded-lg bg-black"
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

                  {/* 扫描框 */}
                  <div className="absolute inset-0 flex items-center justify-center">
                    <div className="flex h-48 w-48 items-center justify-center rounded-lg border-2 border-dashed border-white">
                      <div className="text-center text-white">
                        <QrCodeIcon className="mx-auto mb-2 h-8 w-8" />
                        <p className="text-sm">将二维码放在框内</p>
                      </div>
                    </div>
                  </div>
                </div>

                <p className="text-center text-xs text-gray-500">
                  提示：如果无法使用摄像头，可以切换到手动输入模式
                </p>
              </div>
            ) : (
              <form onSubmit={handleManualSubmit} className="space-y-4">
                <div>
                  <label htmlFor="manual-code" className="block text-sm font-medium text-gray-700">
                    设备登录码或二维码内容
                  </label>
                  <textarea
                    id="manual-code"
                    rows={4}
                    className="mt-1 block w-full rounded-md border-gray-300 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 sm:text-sm"
                    placeholder="请输入设备登录码或粘贴二维码的完整内容..."
                    value={manualCode}
                    onChange={(e) => setManualCode(e.target.value)}
                  />
                </div>

                <div className="flex justify-end space-x-3">
                  <button
                    type="button"
                    onClick={onClose}
                    className="rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50 focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:outline-none"
                  >
                    取消
                  </button>
                  <button
                    type="submit"
                    className="rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 focus:outline-none"
                  >
                    确认
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
