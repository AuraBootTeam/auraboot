import { useState, useEffect, useRef } from 'react';
import { useParams, useSearchParams } from 'react-router';
import {
  PlayIcon,
  PauseIcon,
  StopIcon,
  SpeakerWaveIcon,
  SpeakerXMarkIcon,
  ArrowsPointingOutIcon,
  ArrowsPointingInIcon,
  Cog6ToothIcon,
  XMarkIcon,
  ClockIcon,
  EyeIcon,
  DevicePhoneMobileIcon,
  ComputerDesktopIcon,
  TvIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';

interface PreviewContent {
  id: string;
  type: 'image' | 'video' | 'text' | 'web';
  url?: string;
  content?: string;
  duration: number;
  transition?: {
    type: 'fade' | 'slide' | 'zoom';
    duration: number;
  };
  position?: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
}

interface PreviewData {
  id: string;
  name: string;
  type: 'program' | 'template';
  contents: PreviewContent[];
  settings: {
    backgroundColor: string;
    loopPlay: boolean;
    autoPlay: boolean;
    resolution: {
      width: number;
      height: number;
    };
  };
  totalDuration: number;
}

const Preview = () => {
  const { showErrorToast } = useToast();
  const { id } = useParams();
  const [searchParams] = useSearchParams();
  const type = searchParams.get('type') || 'program';

  const [previewData, setPreviewData] = useState<PreviewData | null>(null);
  const [loading, setLoading] = useState(true);
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentIndex, setCurrentIndex] = useState(0);
  const [currentTime, setCurrentTime] = useState(0);
  const [isMuted, setIsMuted] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [previewMode, setPreviewMode] = useState<'desktop' | 'tablet' | 'mobile' | 'tv'>('desktop');
  const [playbackSpeed, setPlaybackSpeed] = useState(1);

  const previewRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const intervalRef = useRef<NodeJS.Timeout | null>(null);

  useEffect(() => {
    if (id) {
      fetchPreviewData();
    }
  }, [id, type]);

  useEffect(() => {
    if (isPlaying && previewData) {
      intervalRef.current = setInterval(() => {
        setCurrentTime((prev) => {
          const newTime = prev + 1 * playbackSpeed;
          const currentContent = previewData.contents[currentIndex];

          if (currentContent && newTime >= currentContent.duration) {
            // 切换到下一个内容
            const nextIndex = (currentIndex + 1) % previewData.contents.length;
            setCurrentIndex(nextIndex);
            return 0;
          }

          return newTime;
        });
      }, 1000);
    } else {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
    }

    return () => {
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
      }
    };
  }, [isPlaying, currentIndex, previewData, playbackSpeed]);

  const fetchPreviewData = async () => {
    try {
      setLoading(true);
      const endpoint =
        type === 'template' ? `/api/templates/${id}/preview` : `/api/programs/${id}/preview`;
      const response = await fetch(endpoint);
      const data = await response.json();

      if (data.code === '0') {
        setPreviewData(data.data);
      } else {
        showErrorToast('获取预览数据失败');
      }
    } catch (error) {
      console.error('Error fetching preview data:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const handlePlay = () => {
    setIsPlaying(true);
  };

  const handlePause = () => {
    setIsPlaying(false);
  };

  const handleStop = () => {
    setIsPlaying(false);
    setCurrentIndex(0);
    setCurrentTime(0);
  };

  const handleSeek = (index: number) => {
    setCurrentIndex(index);
    setCurrentTime(0);
  };

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      previewRef.current?.requestFullscreen();
      setIsFullscreen(true);
    } else {
      document.exitFullscreen();
      setIsFullscreen(false);
    }
  };

  const getPreviewDimensions = () => {
    switch (previewMode) {
      case 'mobile':
        return { width: '375px', height: '667px' };
      case 'tablet':
        return { width: '768px', height: '1024px' };
      case 'tv':
        return { width: '1920px', height: '1080px', maxWidth: '100%', maxHeight: '60vh' };
      default:
        return { width: '1280px', height: '720px', maxWidth: '100%', maxHeight: '60vh' };
    }
  };

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  const renderContent = (content: PreviewContent) => {
    const style = {
      position: 'absolute' as const,
      left: content.position?.x || 0,
      top: content.position?.y || 0,
      width: content.position?.width || '100%',
      height: content.position?.height || '100%',
    };

    switch (content.type) {
      case 'image':
        return (
          <img src={content.url} alt="Preview content" style={style} className="object-cover" />
        );
      case 'video':
        return (
          <video
            ref={videoRef}
            src={content.url}
            style={style}
            className="object-cover"
            muted={isMuted}
            autoPlay={isPlaying}
            onEnded={() => {
              const nextIndex = (currentIndex + 1) % (previewData?.contents.length || 1);
              setCurrentIndex(nextIndex);
              setCurrentTime(0);
            }}
          />
        );
      case 'text':
        return (
          <div
            style={style}
            className="flex items-center justify-center p-4 text-2xl font-bold text-white"
          >
            {content.content}
          </div>
        );
      case 'web':
        return <iframe src={content.url} style={style} className="border-0" title="Web content" />;
      default:
        return null;
    }
  };

  if (loading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-100">
        <div className="h-32 w-32 animate-spin rounded-full border-b-2 border-indigo-600"></div>
      </div>
    );
  }

  if (!previewData) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-gray-100">
        <div className="text-center">
          <EyeIcon className="mx-auto h-12 w-12 text-gray-400" />
          <h3 className="mt-2 text-sm font-medium text-gray-900">预览数据不存在</h3>
          <p className="mt-1 text-sm text-gray-500">
            请检查{type === 'template' ? '模板' : '节目'}是否存在
          </p>
        </div>
      </div>
    );
  }

  const currentContent = previewData.contents[currentIndex];
  const progress = currentContent ? (currentTime / currentContent.duration) * 100 : 0;

  return (
    <div className="min-h-screen bg-gray-900">
      {/* 顶部工具栏 */}
      <div className="bg-gray-800 p-4 text-white">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <h1 className="text-lg font-medium">{previewData.name}</h1>
            <span className="rounded bg-gray-700 px-2 py-1 text-sm">
              {type === 'template' ? '模板预览' : '节目预览'}
            </span>
          </div>

          <div className="flex items-center space-x-2">
            {/* 预览模式切换 */}
            <div className="flex items-center space-x-1 rounded bg-gray-700 p-1">
              <button
                onClick={() => setPreviewMode('mobile')}
                className={`rounded p-2 ${
                  previewMode === 'mobile' ? 'bg-indigo-600' : 'hover:bg-gray-600'
                }`}
                title="手机预览"
              >
                <DevicePhoneMobileIcon className="h-4 w-4" />
              </button>
              <button
                onClick={() => setPreviewMode('tablet')}
                className={`rounded p-2 ${
                  previewMode === 'tablet' ? 'bg-indigo-600' : 'hover:bg-gray-600'
                }`}
                title="平板预览"
              >
                <ComputerDesktopIcon className="h-4 w-4" />
              </button>
              <button
                onClick={() => setPreviewMode('desktop')}
                className={`rounded p-2 ${
                  previewMode === 'desktop' ? 'bg-indigo-600' : 'hover:bg-gray-600'
                }`}
                title="桌面预览"
              >
                <ComputerDesktopIcon className="h-4 w-4" />
              </button>
              <button
                onClick={() => setPreviewMode('tv')}
                className={`rounded p-2 ${
                  previewMode === 'tv' ? 'bg-indigo-600' : 'hover:bg-gray-600'
                }`}
                title="电视预览"
              >
                <TvIcon className="h-4 w-4" />
              </button>
            </div>

            <button
              onClick={() => setShowSettings(!showSettings)}
              className="rounded p-2 hover:bg-gray-700"
              title="设置"
            >
              <Cog6ToothIcon className="h-5 w-5" />
            </button>

            <button
              onClick={toggleFullscreen}
              className="rounded p-2 hover:bg-gray-700"
              title={isFullscreen ? '退出全屏' : '全屏'}
            >
              {isFullscreen ? (
                <ArrowsPointingInIcon className="h-5 w-5" />
              ) : (
                <ArrowsPointingOutIcon className="h-5 w-5" />
              )}
            </button>

            <button
              onClick={() => window.close()}
              className="rounded p-2 hover:bg-gray-700"
              title="关闭"
            >
              <XMarkIcon className="h-5 w-5" />
            </button>
          </div>
        </div>
      </div>

      {/* 预览区域 */}
      <div className="flex flex-1 items-center justify-center p-8">
        <div className="relative">
          <div
            ref={previewRef}
            className="relative overflow-hidden rounded-lg bg-black shadow-2xl"
            style={{
              ...getPreviewDimensions(),
              backgroundColor: previewData.settings.backgroundColor,
            }}
          >
            {currentContent && renderContent(currentContent)}

            {/* 进度条 */}
            <div className="bg-opacity-50 absolute right-0 bottom-0 left-0 bg-black p-4">
              <div className="flex items-center space-x-4">
                <div className="flex items-center space-x-2">
                  <button
                    onClick={isPlaying ? handlePause : handlePlay}
                    className="bg-opacity-20 hover:bg-opacity-30 rounded-full bg-white p-2"
                  >
                    {isPlaying ? (
                      <PauseIcon className="h-5 w-5 text-white" />
                    ) : (
                      <PlayIcon className="h-5 w-5 text-white" />
                    )}
                  </button>

                  <button
                    onClick={handleStop}
                    className="bg-opacity-20 hover:bg-opacity-30 rounded-full bg-white p-2"
                  >
                    <StopIcon className="h-5 w-5 text-white" />
                  </button>

                  <button
                    onClick={() => setIsMuted(!isMuted)}
                    className="bg-opacity-20 hover:bg-opacity-30 rounded-full bg-white p-2"
                  >
                    {isMuted ? (
                      <SpeakerXMarkIcon className="h-5 w-5 text-white" />
                    ) : (
                      <SpeakerWaveIcon className="h-5 w-5 text-white" />
                    )}
                  </button>
                </div>

                <div className="flex-1">
                  <div className="relative">
                    <div className="bg-opacity-20 h-2 rounded-full bg-white">
                      <div
                        className="h-2 rounded-full bg-indigo-500 transition-all duration-300"
                        style={{ width: `${progress}%` }}
                      />
                    </div>
                  </div>
                </div>

                <div className="flex items-center space-x-2 text-sm text-white">
                  <ClockIcon className="h-4 w-4" />
                  <span>
                    {formatTime(currentTime)} / {formatTime(currentContent?.duration || 0)}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* 底部内容列表 */}
      <div className="bg-gray-800 p-4 text-white">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="text-lg font-medium">内容列表</h3>
          <div className="flex items-center space-x-4">
            <span className="text-sm text-gray-300">
              总时长: {formatTime(previewData.totalDuration)}
            </span>
            <div className="flex items-center space-x-2">
              <label className="text-sm text-gray-300">播放速度:</label>
              <select
                value={playbackSpeed}
                onChange={(e) => setPlaybackSpeed(Number(e.target.value))}
                className="rounded bg-gray-700 px-2 py-1 text-sm text-white"
              >
                <option value={0.5}>0.5x</option>
                <option value={1}>1x</option>
                <option value={1.5}>1.5x</option>
                <option value={2}>2x</option>
              </select>
            </div>
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-4">
          {previewData.contents.map((content, index) => (
            <div
              key={content.id}
              onClick={() => handleSeek(index)}
              className={`cursor-pointer rounded-lg p-3 transition-colors ${
                index === currentIndex ? 'bg-indigo-600' : 'bg-gray-700 hover:bg-gray-600'
              }`}
            >
              <div className="flex items-center space-x-3">
                <div className="flex-shrink-0">
                  {content.type === 'image' && (
                    <div className="flex h-8 w-12 items-center justify-center rounded bg-green-500">
                      <span className="text-xs font-medium">IMG</span>
                    </div>
                  )}
                  {content.type === 'video' && (
                    <div className="flex h-8 w-12 items-center justify-center rounded bg-blue-500">
                      <span className="text-xs font-medium">VID</span>
                    </div>
                  )}
                  {content.type === 'text' && (
                    <div className="flex h-8 w-12 items-center justify-center rounded bg-purple-500">
                      <span className="text-xs font-medium">TXT</span>
                    </div>
                  )}
                  {content.type === 'web' && (
                    <div className="flex h-8 w-12 items-center justify-center rounded bg-orange-500">
                      <span className="text-xs font-medium">WEB</span>
                    </div>
                  )}
                </div>

                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">
                    {content.type === 'text' ? content.content : `内容 ${index + 1}`}
                  </p>
                  <p className="text-xs text-gray-300">{formatTime(content.duration)}</p>
                </div>

                {index === currentIndex && isPlaying && (
                  <div className="flex-shrink-0">
                    <div className="h-2 w-2 animate-pulse rounded-full bg-white"></div>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* 设置面板 */}
      {showSettings && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="w-96 rounded-lg bg-white p-6">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-lg font-medium text-gray-900">预览设置</h3>
              <button
                onClick={() => setShowSettings(false)}
                className="text-gray-400 hover:text-gray-600"
              >
                <XMarkIcon className="h-5 w-5" />
              </button>
            </div>

            <div className="space-y-4">
              <div>
                <label className="mb-2 block text-sm font-medium text-gray-700">分辨率</label>
                <p className="text-sm text-gray-500">
                  {previewData.settings.resolution.width} x {previewData.settings.resolution.height}
                </p>
              </div>

              <div>
                <label className="mb-2 block text-sm font-medium text-gray-700">背景颜色</label>
                <div className="flex items-center space-x-2">
                  <div
                    className="h-8 w-8 rounded border"
                    style={{ backgroundColor: previewData.settings.backgroundColor }}
                  />
                  <span className="text-sm text-gray-500">
                    {previewData.settings.backgroundColor}
                  </span>
                </div>
              </div>

              <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700">循环播放</span>
                <span
                  className={`rounded px-2 py-1 text-xs ${
                    previewData.settings.loopPlay
                      ? 'bg-green-100 text-green-800'
                      : 'bg-gray-100 text-gray-800'
                  }`}
                >
                  {previewData.settings.loopPlay ? '开启' : '关闭'}
                </span>
              </div>

              <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700">自动播放</span>
                <span
                  className={`rounded px-2 py-1 text-xs ${
                    previewData.settings.autoPlay
                      ? 'bg-green-100 text-green-800'
                      : 'bg-gray-100 text-gray-800'
                  }`}
                >
                  {previewData.settings.autoPlay ? '开启' : '关闭'}
                </span>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Preview;
