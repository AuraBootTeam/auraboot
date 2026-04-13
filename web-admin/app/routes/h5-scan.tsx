import { useState, useRef, useEffect, useCallback } from 'react';
import dayjs from 'dayjs';
import { useNavigate, type ActionFunctionArgs } from 'react-router';
import {
  QrCodeIcon,
  CameraIcon,
  XMarkIcon,
  ArrowLeftIcon,
  CheckCircleIcon,
  ExclamationTriangleIcon,
  ShieldCheckIcon,
  InformationCircleIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';
import { useAuth } from '~/contexts/AuthContext';
import { fetchResult } from '~/shared/services/http-client';
import jsQR from 'jsqr';

interface DeviceQrCodeData {
  type: string;
  deviceLoginCode: string;
  deviceCode: string;
  deviceName: string;
  deviceType?: string;
  macAddress?: string;
  ipAddress?: string;
  screenResolution?: string;
  timestamp: number;
}

export const action = async ({ request }: ActionFunctionArgs) => {
  const formData = await request.formData();
  const _deviceLoginCode = formData.get('deviceLoginCode');
  const _action = formData.get('action');

  // 这里应该调用后端API进行设备绑定
  // 暂时返回成功响应
  return {
    success: true,
    data: {
      status: 'success',
      message: '设备绑定成功',
    },
  };
};

export default function H5ScanPage() {
  // 初始化 VConsole (仅在客户端)
  useEffect(() => {
    if (typeof window !== 'undefined') {
      import('vconsole')
        .then((VConsole) => {
          const vConsole = new VConsole.default();
          return () => {
            vConsole.destroy();
          };
        })
        .catch(() => {});
    }
  }, []);

  const navigate = useNavigate();
  const { showErrorToast, showSuccessToast } = useToast();
  const { user, token } = useAuth();

  const [isScanning, setIsScanning] = useState(false);
  const [isProcessing, setIsProcessing] = useState(false);
  const [scanResult, setScanResult] = useState<DeviceQrCodeData | null>(null);
  const [bindingSuccess, setBindingSuccess] = useState(false);
  const [error, setError] = useState<string>('');
  const [cameraError, setCameraError] = useState<string>('');
  const [showPermissionPrompt, setShowPermissionPrompt] = useState(false);
  const [permissionDenied, setPermissionDenied] = useState(false);

  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const scanIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // 检查用户登录状态
  // useEffect(() => {
  //   if (!user) {
  //     navigate('/login');
  //   }
  // }, [user, navigate]);

  // 清理资源
  useEffect(() => {
    return () => {
      stopCamera();
      if (scanIntervalRef.current) {
        clearInterval(scanIntervalRef.current);
      }
    };
  }, []);

  const stopCamera = useCallback(() => {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
    }
    if (scanIntervalRef.current) {
      clearInterval(scanIntervalRef.current);
      scanIntervalRef.current = null;
    }
    setIsScanning(false);
  }, []);

  const checkCameraPermission = async () => {
    try {
      const result = await navigator.permissions.query({ name: 'camera' as PermissionName });
      return result.state;
    } catch (error) {
      // 某些浏览器不支持 permissions API
      return 'prompt';
    }
  };

  const startCamera = useCallback(async () => {
    try {
      setCameraError('');
      setError('');
      setPermissionDenied(false);

      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: 'environment', // 优先使用后置摄像头
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
      });

      // 等待video元素准备就绪的重试机制
      const waitForVideoElement = async (maxRetries = 10, delay = 100) => {
        for (let i = 0; i < maxRetries; i++) {
          if (videoRef.current) {
            return true;
          }
          await new Promise((resolve) => setTimeout(resolve, delay));
        }
        return false;
      };

      const isVideoReady = await waitForVideoElement();

      if (isVideoReady && videoRef.current) {
        videoRef.current.srcObject = stream;
        streamRef.current = stream;
        setIsScanning(true);

        // 等待视频加载完成后开始扫描
        videoRef.current.onloadedmetadata = () => {
          startScanning();
        };
      } else {
        console.error('videoRef.current 在重试后仍为空');
        // 停止媒体流
        stream.getTracks().forEach((track) => track.stop());
        throw new Error('视频元素未能及时准备就绪');
      }
    } catch (error: any) {
      console.error('无法访问摄像头:', error);

      // 检测权限被拒绝的情况
      if (
        error &&
        typeof error === 'object' &&
        'name' in error &&
        (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError')
      ) {
        setPermissionDenied(true);
        setCameraError('摄像头权限被拒绝，请允许访问摄像头后重试');
        showErrorToast('需要摄像头权限才能扫描二维码');
      } else if (
        error &&
        typeof error === 'object' &&
        'name' in error &&
        error.name === 'NotFoundError'
      ) {
        setCameraError('未找到摄像头设备，请检查设备连接');
        showErrorToast('未找到摄像头设备');
      } else if (
        error &&
        typeof error === 'object' &&
        'name' in error &&
        error.name === 'NotReadableError'
      ) {
        setCameraError('摄像头被其他应用占用，请关闭其他应用后重试');
        showErrorToast('摄像头被占用');
      } else {
        setCameraError('无法访问摄像头，请检查权限设置或尝试刷新页面');
        showErrorToast('摄像头访问失败');
      }
    }
  }, [showErrorToast]);

  const handleStartScan = async () => {
    try {
      const permissionState = await checkCameraPermission();
      if (permissionState === 'denied') {
        setPermissionDenied(true);
        setCameraError('摄像头权限被拒绝，请在浏览器设置中允许访问摄像头');
        return;
      }

      if (permissionState === 'prompt') {
        setShowPermissionPrompt(true);
      } else {
        startCamera();
      }
    } catch {
      showErrorToast('启动扫描失败');
    }
  };

  const handlePermissionConfirm = () => {
    setShowPermissionPrompt(false);
    startCamera();
  };

  const handlePermissionCancel = () => {
    setShowPermissionPrompt(false);
  };

  const captureAndAnalyze = () => {
    if (!videoRef.current || !canvasRef.current) {
      console.warn('captureAndAnalyze: video或canvas引用为空');
      return;
    }

    const video = videoRef.current;
    const canvas = canvasRef.current;
    const context = canvas.getContext('2d');

    if (!context) {
      console.warn('captureAndAnalyze: 无法获取canvas context');
      return;
    }

    // 设置canvas尺寸
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    // 绘制当前帧
    context.drawImage(video, 0, 0, canvas.width, canvas.height);

    try {
      // 获取图像数据
      const imageData = context.getImageData(0, 0, canvas.width, canvas.height);

      // 使用jsQR解析二维码
      const code = jsQR(imageData.data, imageData.width, imageData.height);

      if (code) {
        handleQrCodeDetected(code.data);
      }
    } catch {
      // QR parsing error - silently continue scanning
    }
  };

  const scanQRCode = useCallback(() => {
    if (!videoRef.current || !canvasRef.current || isProcessing) {
      return;
    }

    captureAndAnalyze();
  }, [isProcessing]);

  const startScanning = useCallback(() => {
    if (scanIntervalRef.current) {
      clearInterval(scanIntervalRef.current);
    }

    scanIntervalRef.current = setInterval(() => {
      scanQRCode();
    }, 300); // 每300ms扫描一次
  }, [scanQRCode]);

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

  const handleQrCodeDetected = useCallback(
    (qrText: string) => {
      if (isProcessing) {
        console.warn('handleQrCodeDetected: 正在处理中，忽略新的二维码');
        return;
      }

      setIsProcessing(true);
      stopCamera();

      const deviceData = parseQrCodeData(qrText);
      if (deviceData) {
        setScanResult(deviceData);
        showSuccessToast('二维码识别成功！');
        // 二维码解析成功后重置 isProcessing，只有在用户点击确认绑定时才设置为 true
        setIsProcessing(false);
      } else {
        setError('无效的设备二维码，请扫描TV设备上显示的登录二维码');
        showErrorToast('无效的二维码');
        setIsProcessing(false);
      }
    },
    [isProcessing, stopCamera, showErrorToast, showSuccessToast],
  );

  const handleConfirmBinding = async () => {
    if (!scanResult || !user) {
      return;
    }
    setIsProcessing(true);

    try {
      const requestBody = {
        deviceLoginCode: scanResult.deviceLoginCode,
        action: 'confirm',
        deviceCode: scanResult.deviceCode,
        deviceName: scanResult.deviceName,
        deviceType: scanResult.deviceType,
        macAddress: scanResult.macAddress,
        ipAddress: scanResult.ipAddress,
        screenResolution: scanResult.screenResolution,
        token: token,
      };

      const response = await fetchResult('/api/device/bind-user', {
        method: 'post',
        params: requestBody,
        token: token,
      });

      if (response && response.data) {
        showSuccessToast('设备绑定成功！');
        setBindingSuccess(true);
        setIsProcessing(false);
      } else {
        throw new Error('设备绑定失败');
      }
    } catch (error) {
      console.error('设备绑定失败:', error);
      console.error(`设备绑定失败: ${error}`);
      setError(error instanceof Error ? error.message : '设备绑定失败');
      showErrorToast('设备绑定失败');
      setIsProcessing(false);
    }
  };

  const handleCancelBinding = () => {
    setScanResult(null);
    setError('');
    setIsProcessing(false);
  };

  const handleRetry = () => {
    setError('');
    setCameraError('');
    setScanResult(null);
    setIsProcessing(false);
    startCamera();
  };

  const formatTimestamp = (timestamp: number) => {
    return dayjs(timestamp).format('YYYY-MM-DD HH:mm:ss');
  };

  return (
    <div className="flex min-h-screen flex-col bg-black">
      {/* Header */}
      <div className="relative z-10 flex items-center justify-between bg-white px-4 py-3 shadow-sm">
        <button
          onClick={() => navigate('/login')}
          className="flex items-center text-gray-600 hover:text-gray-900"
        >
          <ArrowLeftIcon className="mr-1 h-5 w-5" />
          返回
        </button>
        <h1 className="text-lg font-semibold text-gray-900">扫描设备二维码</h1>
        <div className="w-16"></div>
      </div>

      {/* Main Content */}
      <div className="relative flex-1">
        {/* Camera View - 始终渲染video元素，通过CSS控制显示 */}
        <video
          ref={videoRef}
          autoPlay
          playsInline
          muted
          className={`h-full w-full object-cover ${isScanning ? 'block' : 'hidden'}`}
        />
        <canvas ref={canvasRef} className="hidden" />

        {isScanning && (
          <>
            {/* Scan Overlay */}
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="relative">
                {/* Scan Frame */}
                <div className="relative h-64 w-64 rounded-2xl border-2 border-white">
                  {/* Corner Indicators */}
                  <div className="absolute -top-1 -left-1 h-8 w-8 rounded-tl-lg border-t-4 border-l-4 border-green-400"></div>
                  <div className="absolute -top-1 -right-1 h-8 w-8 rounded-tr-lg border-t-4 border-r-4 border-green-400"></div>
                  <div className="absolute -bottom-1 -left-1 h-8 w-8 rounded-bl-lg border-b-4 border-l-4 border-green-400"></div>
                  <div className="absolute -right-1 -bottom-1 h-8 w-8 rounded-br-lg border-r-4 border-b-4 border-green-400"></div>

                  {/* Scanning Line */}
                  <div className="absolute inset-0 overflow-hidden rounded-2xl">
                    <div
                      className="h-0.5 w-full animate-pulse bg-green-400"
                      style={{
                        animation: 'scan 2s linear infinite',
                        transformOrigin: 'center',
                      }}
                    ></div>
                  </div>
                </div>

                {/* Instructions */}
                <div className="mt-6 text-center">
                  <p className="mb-2 text-lg font-medium text-white">将二维码放入框内</p>
                  <p className="text-sm text-gray-300">请对准TV设备上显示的登录二维码</p>
                </div>
              </div>
            </div>

            {/* Stop Button */}
            <div className="absolute bottom-8 left-1/2 -translate-x-1/2 transform">
              <button
                onClick={stopCamera}
                className="rounded-full bg-red-600 p-4 text-white shadow-lg transition-colors hover:bg-red-700"
              >
                <XMarkIcon className="h-6 w-6" />
              </button>
            </div>
          </>
        )}

        {/* Start Scan Button */}
        {!isScanning && !scanResult && !cameraError && (
          <div className="flex flex-1 flex-col items-center justify-center px-4">
            <div className="mb-8 text-center">
              <div className="mx-auto mb-4 flex h-20 w-20 items-center justify-center rounded-full bg-indigo-600">
                <QrCodeIcon className="h-10 w-10 text-white" />
              </div>
              <h2 className="mb-2 text-xl font-bold text-white">准备扫描</h2>
              <p className="text-sm text-gray-300">点击下方按钮开始扫描TV设备二维码</p>
            </div>

            <button
              onClick={() => {
                handleStartScan();
              }}
              className="flex items-center rounded-xl bg-indigo-600 px-8 py-4 text-lg font-medium text-white transition-colors hover:bg-indigo-700"
            >
              <CameraIcon className="mr-2 h-6 w-6" />
              开始扫描
            </button>
          </div>
        )}

        {/* Camera Error */}
        {cameraError && (
          <div className="flex flex-1 flex-col items-center justify-center px-4">
            <div className="mb-8 text-center">
              <ExclamationTriangleIcon className="mx-auto mb-4 h-16 w-16 text-red-500" />
              <h2 className="mb-2 text-xl font-bold text-white">
                {permissionDenied ? '需要摄像头权限' : '摄像头访问失败'}
              </h2>
              <p className="mb-4 text-sm text-gray-300">{cameraError}</p>

              {permissionDenied && (
                <div className="mb-6 rounded-lg border border-yellow-600 bg-yellow-900/50 p-4 text-left">
                  <h3 className="mb-2 flex items-center font-medium text-yellow-400">
                    <InformationCircleIcon className="mr-2 h-5 w-5" />
                    如何开启摄像头权限？
                  </h3>
                  <div className="space-y-2 text-sm text-yellow-200">
                    <p>
                      • <strong>Chrome/Edge:</strong> 点击地址栏左侧的摄像头图标，选择"允许"
                    </p>
                    <p>
                      • <strong>Safari:</strong> 在地址栏点击"网站设置"，允许摄像头访问
                    </p>
                    <p>
                      • <strong>Firefox:</strong> 点击地址栏左侧的盾牌图标，允许摄像头权限
                    </p>
                  </div>
                </div>
              )}
            </div>

            <div className="flex space-x-3">
              <button
                onClick={handleRetry}
                className="rounded-xl bg-indigo-600 px-6 py-3 font-medium text-white transition-colors hover:bg-indigo-700"
              >
                重试
              </button>
              {permissionDenied && (
                <button
                  onClick={() => window.location.reload()}
                  className="rounded-xl bg-gray-600 px-6 py-3 font-medium text-white transition-colors hover:bg-gray-700"
                >
                  刷新页面
                </button>
              )}
            </div>
          </div>
        )}

        {/* Scan Result */}
        {scanResult && !bindingSuccess && (
          <div className="flex flex-1 flex-col items-center justify-center px-4">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
              <div className="mb-6 text-center">
                <CheckCircleIcon className="mx-auto mb-3 h-12 w-12 text-green-500" />
                <h2 className="mb-2 text-xl font-bold text-gray-900">识别成功</h2>
                <p className="text-sm text-gray-600">确认绑定以下设备？</p>
              </div>

              {/* Device Info */}
              <div className="mb-6 rounded-xl bg-gray-50 p-4">
                <h3 className="mb-2 font-medium text-gray-900">{scanResult.deviceName}</h3>
                <p className="mb-1 text-sm text-gray-600">设备编码: {scanResult.deviceCode}</p>
                {scanResult.deviceType && (
                  <p className="mb-1 text-sm text-gray-600">设备类型: {scanResult.deviceType}</p>
                )}
                <p className="text-xs text-gray-500">
                  扫描时间: {formatTimestamp(scanResult.timestamp)}
                </p>
              </div>

              {/* User Info */}
              <div className="mb-6 rounded-xl bg-blue-50 p-4">
                <h4 className="mb-2 text-sm font-medium text-blue-900">登录账户</h4>
                <div className="flex items-center">
                  {user?.avatar && (
                    <img
                      className="mr-3 h-8 w-8 rounded-full"
                      src={user.avatar}
                      alt={user.nickname || user.username}
                    />
                  )}
                  <div>
                    <p className="text-sm font-medium text-blue-900">
                      {user?.nickname || user?.username}
                    </p>
                    <p className="text-xs text-blue-700">{user?.email}</p>
                  </div>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex space-x-3">
                <button
                  onClick={handleCancelBinding}
                  disabled={isProcessing}
                  className="flex-1 rounded-xl border border-gray-300 bg-white px-4 py-3 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50"
                >
                  取消
                </button>
                <button
                  onClick={handleConfirmBinding}
                  disabled={isProcessing}
                  className="flex-1 rounded-xl border border-transparent bg-indigo-600 px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:opacity-50"
                >
                  {isProcessing ? '绑定中...' : '确认绑定'}
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Binding Success */}
        {bindingSuccess && scanResult && (
          <div className="flex flex-1 flex-col items-center justify-center px-4">
            <div className="w-full max-w-sm rounded-2xl bg-white p-6 shadow-lg">
              <div className="mb-6 text-center">
                <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-green-100">
                  <CheckCircleIcon className="h-10 w-10 text-green-600" />
                </div>
                <h2 className="mb-2 text-2xl font-bold text-gray-900">绑定成功！</h2>
                <p className="text-sm text-gray-600">设备已成功绑定到您的账户</p>
              </div>

              {/* Success Device Info */}
              <div className="mb-6 rounded-xl border border-green-200 bg-green-50 p-4">
                <div className="mb-3 flex items-center">
                  <CheckCircleIcon className="mr-2 h-5 w-5 text-green-600" />
                  <h3 className="font-medium text-green-900">已绑定设备</h3>
                </div>
                <h4 className="mb-2 font-medium text-gray-900">{scanResult.deviceName}</h4>
                <p className="mb-1 text-sm text-gray-600">设备编码: {scanResult.deviceCode}</p>
                {scanResult.deviceType && (
                  <p className="mb-1 text-sm text-gray-600">设备类型: {scanResult.deviceType}</p>
                )}
                <p className="text-xs text-gray-500">
                  绑定时间: {dayjs().format('YYYY-MM-DD HH:mm:ss')}
                </p>
              </div>

              {/* User Info */}
              <div className="mb-6 rounded-xl bg-blue-50 p-4">
                <h4 className="mb-2 text-sm font-medium text-blue-900">绑定账户</h4>
                <div className="flex items-center">
                  {user?.avatar && (
                    <img
                      className="mr-3 h-8 w-8 rounded-full"
                      src={user.avatar}
                      alt={user.nickname || user.username}
                    />
                  )}
                  <div>
                    <p className="text-sm font-medium text-blue-900">
                      {user?.nickname || user?.username}
                    </p>
                    <p className="text-xs text-blue-700">{user?.email}</p>
                  </div>
                </div>
              </div>

              {/* Action Buttons */}
              <div className="flex space-x-3">
                <button
                  onClick={() => {
                    setBindingSuccess(false);
                    setScanResult(null);
                    setError('');
                  }}
                  className="flex-1 rounded-xl border border-gray-300 bg-white px-4 py-3 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
                >
                  继续扫描
                </button>
                <button
                  onClick={() => navigate('/devices')}
                  className="flex-1 rounded-xl border border-transparent bg-green-600 px-4 py-3 text-sm font-medium text-white transition-colors hover:bg-green-700"
                >
                  查看设备
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Error Message */}
        {error && (
          <div className="absolute right-4 bottom-20 left-4">
            <div className="rounded-xl bg-red-600 p-4 text-white">
              <p className="text-sm">{error}</p>
              <button onClick={handleRetry} className="mt-2 text-sm underline">
                重新扫描
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Permission Prompt Modal */}
      {showPermissionPrompt && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4">
          <div className="w-full max-w-sm rounded-2xl border border-gray-700 bg-gray-800 p-6">
            <div className="mb-6 text-center">
              <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-blue-600">
                <ShieldCheckIcon className="h-8 w-8 text-white" />
              </div>
              <h3 className="mb-2 text-xl font-bold text-white">需要摄像头权限</h3>
              <p className="text-sm leading-relaxed text-gray-300">
                为了扫描TV设备上的二维码，我们需要访问您的摄像头。您的隐私和数据安全是我们的首要关注。
              </p>
            </div>

            <div className="mb-6 rounded-lg border border-blue-600/50 bg-blue-900/30 p-4">
              <div className="flex items-start">
                <InformationCircleIcon className="mt-0.5 mr-3 h-5 w-5 flex-shrink-0 text-blue-400" />
                <div className="text-sm text-blue-200">
                  <p className="mb-1 font-medium">权限用途说明：</p>
                  <ul className="space-y-1 text-xs">
                    <li>• 仅用于扫描设备二维码</li>
                    <li>• 不会录制或存储视频</li>
                  </ul>
                </div>
              </div>
            </div>

            <div className="flex space-x-3">
              <button
                onClick={handlePermissionCancel}
                className="flex-1 rounded-xl bg-gray-600 py-3 font-medium text-white transition-colors hover:bg-gray-700"
              >
                取消
              </button>
              <button
                onClick={handlePermissionConfirm}
                className="flex-1 rounded-xl bg-blue-600 py-3 font-medium text-white transition-colors hover:bg-blue-700"
              >
                允许访问
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Bottom Safe Area */}
      <div className="h-8 bg-black"></div>

      {/* Custom Styles */}
      <style>{`
        @keyframes scan {
          0% { transform: translateY(0); }
          100% { transform: translateY(256px); }
        }
      `}</style>
    </div>
  );
}

// 添加移动端优化的meta标签
export const meta = () => [
  { title: 'H5扫码登录' },
  {
    name: 'viewport',
    content: 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no',
  },
  { name: 'format-detection', content: 'telephone=no' },
  { name: 'apple-mobile-web-app-capable', content: 'yes' },
  { name: 'apple-mobile-web-app-status-bar-style', content: 'black-translucent' },
];
