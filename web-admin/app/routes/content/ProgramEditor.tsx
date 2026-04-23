import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  TrashIcon,
  ArrowUpIcon,
  ArrowDownIcon,
  PlayIcon,
  PauseIcon,
  EyeIcon,
  DocumentDuplicateIcon,
  ClockIcon,
  PhotoIcon,
  VideoCameraIcon,
  MusicalNoteIcon,
  DocumentIcon,
} from '@heroicons/react/24/outline';
import { useToast } from '~/contexts/ToastContext';

interface ProgramContent {
  id: string;
  contentId: string;
  contentName: string;
  contentType: 'image' | 'video' | 'audio' | 'text';
  contentUrl: string;
  thumbnailUrl?: string;
  duration: number; // 播放时长（秒）
  order: number;
  transition?: {
    type: 'fade' | 'slide' | 'zoom' | 'none';
    duration: number;
  };
  position?: {
    x: number;
    y: number;
    width: number;
    height: number;
  };
  textContent?: {
    text: string;
    fontSize: number;
    fontColor: string;
    backgroundColor: string;
    alignment: 'left' | 'center' | 'right';
  };
}

interface Program {
  id?: string;
  name: string;
  description?: string;
  totalDuration: number;
  contents: ProgramContent[];
  settings: {
    loopPlay: boolean;
    autoPlay: boolean;
    backgroundColor: string;
    resolution: {
      width: number;
      height: number;
    };
  };
}

interface ContentLibraryItem {
  id: string;
  name: string;
  type: 'image' | 'video' | 'audio' | 'document';
  url: string;
  thumbnailUrl?: string;
  duration?: number;
  size: number;
}

const ProgramEditor = () => {
  const { showErrorToast, showSuccessToast } = useToast();
  const { programId } = useParams();
  const navigate = useNavigate();
  const [program, setProgram] = useState<Program>({
    name: '',
    description: '',
    totalDuration: 0,
    contents: [],
    settings: {
      loopPlay: true,
      autoPlay: true,
      backgroundColor: '#000000',
      resolution: {
        width: 1920,
        height: 1080,
      },
    },
  });
  const [libraryItems, setLibraryItems] = useState<ContentLibraryItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [previewing, setPreviewing] = useState(false);
  const [currentPreviewIndex, setCurrentPreviewIndex] = useState(0);
  const [showLibrary, setShowLibrary] = useState(false);
  const [showTextEditor, setShowTextEditor] = useState(false);
  const [textForm, setTextForm] = useState({
    text: '',
    fontSize: 24,
    fontColor: '#ffffff',
    backgroundColor: 'transparent',
    alignment: 'center' as 'left' | 'center' | 'right',
  });

  useEffect(() => {
    if (programId && programId !== 'new') {
      fetchProgram();
    }
    fetchLibraryItems();
  }, [programId]);

  useEffect(() => {
    // 计算总时长
    const total = program.contents.reduce((sum, content) => sum + content.duration, 0);
    setProgram((prev) => ({ ...prev, totalDuration: total }));
  }, [program.contents]);

  const fetchProgram = async () => {
    try {
      setLoading(true);
      const response = await fetch(`/api/programs/${programId}`);
      const data = await response.json();

      if (data.success) {
        setProgram(data.data);
      } else {
        showErrorToast('获取节目信息失败');
      }
    } catch (error) {
      console.error('Error fetching program:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  const fetchLibraryItems = async () => {
    try {
      const response = await fetch('/api/contents?pageSize=100');
      const data = await response.json();

      if (data.success) {
        setLibraryItems(data.data.items);
      }
    } catch (error) {
      console.error('Error fetching library items:', error);
    }
  };

  const handleSave = async () => {
    if (!program.name.trim()) {
      showErrorToast('请输入节目名称');
      return;
    }

    if (program.contents.length === 0) {
      showErrorToast('请添加至少一个内容项');
      return;
    }

    try {
      setSaving(true);
      const method = programId && programId !== 'new' ? 'put' : 'post';
      const url = programId && programId !== 'new' ? `/api/programs/${programId}` : '/api/programs';

      const response = await fetch(url, {
        method,
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(program),
      });

      const data = await response.json();

      if (data.success) {
        showSuccessToast('节目保存成功');
        if (method === 'post') {
          navigate(`/content/program/${data.data.id}`);
        }
      } else {
        showErrorToast(data.message || '保存失败');
      }
    } catch (error) {
      console.error('Error saving program:', error);
      showErrorToast('网络错误，请稍后重试');
    } finally {
      setSaving(false);
    }
  };

  const addContentFromLibrary = (item: ContentLibraryItem) => {
    const newContent: ProgramContent = {
      id: `content_${Date.now()}`,
      contentId: item.id,
      contentName: item.name,
      contentType: item.type as 'image' | 'video' | 'audio',
      contentUrl: item.url,
      thumbnailUrl: item.thumbnailUrl,
      duration: item.duration || (item.type === 'image' ? 10 : 30),
      order: program.contents.length,
      transition: {
        type: 'fade',
        duration: 1,
      },
    };

    setProgram((prev) => ({
      ...prev,
      contents: [...prev.contents, newContent],
    }));
    setShowLibrary(false);
  };

  const addTextContent = () => {
    if (!textForm.text.trim()) {
      showErrorToast('请输入文本内容');
      return;
    }

    const newContent: ProgramContent = {
      id: `text_${Date.now()}`,
      contentId: '',
      contentName: `文本: ${textForm.text.substring(0, 20)}...`,
      contentType: 'text',
      contentUrl: '',
      duration: 10,
      order: program.contents.length,
      textContent: { ...textForm },
      transition: {
        type: 'fade',
        duration: 1,
      },
    };

    setProgram((prev) => ({
      ...prev,
      contents: [...prev.contents, newContent],
    }));
    setShowTextEditor(false);
    setTextForm({
      text: '',
      fontSize: 24,
      fontColor: '#ffffff',
      backgroundColor: 'transparent',
      alignment: 'center',
    });
  };

  const removeContent = (index: number) => {
    setProgram((prev) => ({
      ...prev,
      contents: prev.contents
        .filter((_, i) => i !== index)
        .map((content, i) => ({
          ...content,
          order: i,
        })),
    }));
  };

  const moveContent = (index: number, direction: 'up' | 'down') => {
    const newContents = [...program.contents];
    const targetIndex = direction === 'up' ? index - 1 : index + 1;

    if (targetIndex >= 0 && targetIndex < newContents.length) {
      [newContents[index], newContents[targetIndex]] = [
        newContents[targetIndex],
        newContents[index],
      ];

      // 更新顺序
      newContents.forEach((content, i) => {
        content.order = i;
      });

      setProgram((prev) => ({ ...prev, contents: newContents }));
    }
  };

  const duplicateContent = (index: number) => {
    const originalContent = program.contents[index];
    const duplicatedContent: ProgramContent = {
      ...originalContent,
      id: `${originalContent.id}_copy_${Date.now()}`,
      order: index + 1,
    };

    const newContents = [...program.contents];
    newContents.splice(index + 1, 0, duplicatedContent);

    // 更新后续内容的顺序
    newContents.forEach((content, i) => {
      content.order = i;
    });

    setProgram((prev) => ({ ...prev, contents: newContents }));
  };

  const updateContentDuration = (index: number, duration: number) => {
    const newContents = [...program.contents];
    newContents[index].duration = duration;
    setProgram((prev) => ({ ...prev, contents: newContents }));
  };

  const startPreview = () => {
    if (program.contents.length === 0) {
      showErrorToast('没有内容可以预览');
      return;
    }
    setPreviewing(true);
    setCurrentPreviewIndex(0);
  };

  const stopPreview = () => {
    setPreviewing(false);
    setCurrentPreviewIndex(0);
  };

  const getContentIcon = (type: string) => {
    switch (type) {
      case 'image':
        return <PhotoIcon className="h-5 w-5 text-green-500" />;
      case 'video':
        return <VideoCameraIcon className="h-5 w-5 text-blue-500" />;
      case 'audio':
        return <MusicalNoteIcon className="h-5 w-5 text-purple-500" />;
      case 'text':
        return <DocumentIcon className="h-5 w-5 text-orange-500" />;
      default:
        return <DocumentIcon className="h-5 w-5 text-gray-500" />;
    }
  };

  const formatDuration = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  if (loading) {
    return (
      <div className="px-4 sm:px-6 lg:px-8">
        <div className="animate-pulse">
          <div className="mb-4 h-8 w-1/4 rounded bg-gray-200"></div>
          <div className="mb-8 h-4 w-1/2 rounded bg-gray-200"></div>
          <div className="h-96 rounded bg-gray-200"></div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 sm:px-6 lg:px-8">
      {/* 页面头部 */}
      <div className="mb-8 sm:flex sm:items-center sm:justify-between">
        <div className="flex-1">
          <input
            type="text"
            value={program.name}
            onChange={(e) => setProgram((prev) => ({ ...prev, name: e.target.value }))}
            placeholder="输入节目名称..."
            className="border-none bg-transparent p-0 text-2xl font-semibold text-gray-900 outline-none focus:ring-0"
          />
          <textarea
            value={program.description || ''}
            onChange={(e) => setProgram((prev) => ({ ...prev, description: e.target.value }))}
            placeholder="节目描述（可选）..."
            className="mt-2 resize-none border-none bg-transparent p-0 text-sm text-gray-700 outline-none focus:ring-0"
            rows={2}
          />
        </div>
        <div className="mt-4 flex items-center space-x-3 sm:mt-0">
          <div className="text-sm text-gray-500">
            总时长: {formatDuration(program.totalDuration)}
          </div>
          <button
            onClick={previewing ? stopPreview : startPreview}
            className="inline-flex items-center rounded-md border border-gray-300 bg-white px-3 py-2 text-sm leading-4 font-medium text-gray-700 shadow-sm hover:bg-gray-50"
          >
            {previewing ? (
              <>
                <PauseIcon className="mr-1 h-4 w-4" />
                停止预览
              </>
            ) : (
              <>
                <PlayIcon className="mr-1 h-4 w-4" />
                预览
              </>
            )}
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="inline-flex items-center rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700 disabled:opacity-50"
          >
            {saving ? '保存中...' : '保存节目'}
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-4">
        {/* 预览区域 */}
        <div className="lg:col-span-3">
          <div className="rounded-lg bg-white shadow">
            <div className="border-b border-gray-200 px-6 py-4">
              <div className="flex items-center justify-between">
                <h3 className="text-lg font-medium text-gray-900">预览区域</h3>
                <div className="flex items-center space-x-2 text-sm text-gray-500">
                  <span>
                    {program.settings.resolution.width} × {program.settings.resolution.height}
                  </span>
                </div>
              </div>
            </div>
            <div className="p-6">
              <div
                className="relative overflow-hidden rounded-lg border-2 border-dashed border-gray-300"
                style={{
                  aspectRatio: `${program.settings.resolution.width}/${program.settings.resolution.height}`,
                  backgroundColor: program.settings.backgroundColor,
                  minHeight: '300px',
                }}
              >
                {program.contents.length === 0 ? (
                  <div className="absolute inset-0 flex items-center justify-center">
                    <div className="text-center">
                      <EyeIcon className="mx-auto h-12 w-12 text-gray-400" />
                      <h3 className="mt-2 text-sm font-medium text-gray-900">预览区域</h3>
                      <p className="mt-1 text-sm text-gray-500">添加内容后可在此预览效果</p>
                    </div>
                  </div>
                ) : (
                  <div className="absolute inset-0 flex items-center justify-center">
                    {previewing && program.contents[currentPreviewIndex] ? (
                      <div className="flex h-full w-full items-center justify-center">
                        {program.contents[currentPreviewIndex].contentType === 'image' ? (
                          <img
                            src={program.contents[currentPreviewIndex].contentUrl}
                            alt={program.contents[currentPreviewIndex].contentName}
                            className="max-h-full max-w-full object-contain"
                          />
                        ) : program.contents[currentPreviewIndex].contentType === 'text' ? (
                          <div
                            className="rounded p-4"
                            style={{
                              fontSize: `${program.contents[currentPreviewIndex].textContent?.fontSize}px`,
                              color: program.contents[currentPreviewIndex].textContent?.fontColor,
                              backgroundColor:
                                program.contents[currentPreviewIndex].textContent?.backgroundColor,
                              textAlign:
                                program.contents[currentPreviewIndex].textContent?.alignment,
                            }}
                          >
                            {program.contents[currentPreviewIndex].textContent?.text}
                          </div>
                        ) : (
                          <div className="text-center text-white">
                            {getContentIcon(program.contents[currentPreviewIndex].contentType)}
                            <p className="mt-2">
                              {program.contents[currentPreviewIndex].contentName}
                            </p>
                          </div>
                        )}
                      </div>
                    ) : (
                      <div className="text-center text-gray-500">
                        <PlayIcon className="mx-auto h-12 w-12" />
                        <p className="mt-2">点击预览按钮开始播放</p>
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* 控制面板 */}
        <div className="space-y-6">
          {/* 添加内容 */}
          <div className="rounded-lg bg-white shadow">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">添加内容</h3>
            </div>
            <div className="space-y-3 p-6">
              <button
                onClick={() => setShowLibrary(true)}
                className="flex w-full items-center justify-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50"
              >
                <PhotoIcon className="mr-2 h-4 w-4" />
                从内容库添加
              </button>
              <button
                onClick={() => setShowTextEditor(true)}
                className="flex w-full items-center justify-center rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-sm hover:bg-gray-50"
              >
                <DocumentIcon className="mr-2 h-4 w-4" />
                添加文本
              </button>
            </div>
          </div>

          {/* 节目设置 */}
          <div className="rounded-lg bg-white shadow">
            <div className="border-b border-gray-200 px-6 py-4">
              <h3 className="text-lg font-medium text-gray-900">节目设置</h3>
            </div>
            <div className="space-y-4 p-6">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium text-gray-700">循环播放</label>
                <input
                  type="checkbox"
                  checked={program.settings.loopPlay}
                  onChange={(e) =>
                    setProgram((prev) => ({
                      ...prev,
                      settings: { ...prev.settings, loopPlay: e.target.checked },
                    }))
                  }
                  className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                />
              </div>
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium text-gray-700">自动播放</label>
                <input
                  type="checkbox"
                  checked={program.settings.autoPlay}
                  onChange={(e) =>
                    setProgram((prev) => ({
                      ...prev,
                      settings: { ...prev.settings, autoPlay: e.target.checked },
                    }))
                  }
                  className="h-4 w-4 rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700">背景颜色</label>
                <input
                  type="color"
                  value={program.settings.backgroundColor}
                  onChange={(e) =>
                    setProgram((prev) => ({
                      ...prev,
                      settings: { ...prev.settings, backgroundColor: e.target.value },
                    }))
                  }
                  className="h-8 w-full rounded border border-gray-300"
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* 内容时间轴 */}
      <div className="mt-8 rounded-lg bg-white shadow">
        <div className="border-b border-gray-200 px-6 py-4">
          <h3 className="text-lg font-medium text-gray-900">内容时间轴</h3>
        </div>
        <div className="p-6">
          {program.contents.length === 0 ? (
            <div className="py-8 text-center">
              <ClockIcon className="mx-auto h-12 w-12 text-gray-400" />
              <h3 className="mt-2 text-sm font-medium text-gray-900">暂无内容</h3>
              <p className="mt-1 text-sm text-gray-500">开始添加第一个内容项</p>
            </div>
          ) : (
            <div className="space-y-3">
              {program.contents.map((content, index) => (
                <div
                  key={content.id}
                  className="flex items-center space-x-4 rounded-lg border border-gray-200 p-4"
                >
                  <div className="flex-shrink-0">{getContentIcon(content.contentType)}</div>
                  <div className="min-w-0 flex-1">
                    <h4 className="truncate text-sm font-medium text-gray-900">
                      {content.contentName}
                    </h4>
                    <p className="text-sm text-gray-500">
                      {content.contentType === 'text' ? '文本内容' : '媒体文件'}
                    </p>
                  </div>
                  <div className="flex items-center space-x-2">
                    <input
                      type="number"
                      value={content.duration}
                      onChange={(e) => updateContentDuration(index, parseInt(e.target.value) || 0)}
                      className="w-16 rounded border border-gray-300 px-2 py-1 text-sm"
                      min="1"
                    />
                    <span className="text-xs text-gray-500">秒</span>
                  </div>
                  <div className="flex items-center space-x-1">
                    <button
                      onClick={() => moveContent(index, 'up')}
                      disabled={index === 0}
                      className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-50"
                    >
                      <ArrowUpIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => moveContent(index, 'down')}
                      disabled={index === program.contents.length - 1}
                      className="p-1 text-gray-400 hover:text-gray-600 disabled:opacity-50"
                    >
                      <ArrowDownIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => duplicateContent(index)}
                      className="p-1 text-gray-400 hover:text-blue-600"
                    >
                      <DocumentDuplicateIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => removeContent(index)}
                      className="p-1 text-gray-400 hover:text-red-600"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* 内容库模态框 */}
      {showLibrary && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-10 mx-auto w-4/5 max-w-4xl rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">选择内容</h3>
              <div className="max-h-96 overflow-y-auto">
                <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
                  {libraryItems.map((item) => (
                    <div
                      key={item.id}
                      className="cursor-pointer rounded-lg border border-gray-200 p-4 hover:bg-gray-50"
                      onClick={() => addContentFromLibrary(item)}
                    >
                      <div className="flex items-center space-x-3">
                        {getContentIcon(item.type)}
                        <div className="min-w-0 flex-1">
                          <h4 className="truncate text-sm font-medium text-gray-900">
                            {item.name}
                          </h4>
                          <p className="text-xs text-gray-500">{item.type}</p>
                        </div>
                      </div>
                      {item.type === 'image' && item.thumbnailUrl && (
                        <img
                          src={item.thumbnailUrl}
                          alt={item.name}
                          className="mt-2 h-20 w-full rounded object-cover"
                        />
                      )}
                    </div>
                  ))}
                </div>
              </div>
              <div className="mt-6 flex justify-end">
                <button
                  onClick={() => setShowLibrary(false)}
                  className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  取消
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 文本编辑器模态框 */}
      {showTextEditor && (
        <div className="bg-opacity-50 fixed inset-0 z-50 h-full w-full overflow-y-auto bg-gray-600">
          <div className="relative top-20 mx-auto w-96 rounded-md border bg-white p-5 shadow-lg">
            <div className="mt-3">
              <h3 className="mb-4 text-lg font-medium text-gray-900">添加文本</h3>
              <div className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700">文本内容</label>
                  <textarea
                    value={textForm.text}
                    onChange={(e) => setTextForm({ ...textForm, text: e.target.value })}
                    rows={3}
                    className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    placeholder="输入要显示的文本..."
                  />
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-gray-700">字体大小</label>
                    <input
                      type="number"
                      value={textForm.fontSize}
                      onChange={(e) =>
                        setTextForm({ ...textForm, fontSize: parseInt(e.target.value) || 24 })
                      }
                      className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                      min="12"
                      max="72"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700">对齐方式</label>
                    <select
                      value={textForm.alignment}
                      onChange={(e) =>
                        setTextForm({
                          ...textForm,
                          alignment: e.target.value as 'left' | 'center' | 'right',
                        })
                      }
                      className="mt-1 block w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 focus:outline-none"
                    >
                      <option value="left">左对齐</option>
                      <option value="center">居中</option>
                      <option value="right">右对齐</option>
                    </select>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-gray-700">字体颜色</label>
                    <input
                      type="color"
                      value={textForm.fontColor}
                      onChange={(e) => setTextForm({ ...textForm, fontColor: e.target.value })}
                      className="mt-1 block h-8 w-full rounded border border-gray-300"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700">背景颜色</label>
                    <input
                      type="color"
                      value={
                        textForm.backgroundColor === 'transparent'
                          ? '#000000'
                          : textForm.backgroundColor
                      }
                      onChange={(e) =>
                        setTextForm({ ...textForm, backgroundColor: e.target.value })
                      }
                      className="mt-1 block h-8 w-full rounded border border-gray-300"
                    />
                  </div>
                </div>
              </div>
              <div className="mt-6 flex justify-end space-x-3">
                <button
                  onClick={() => setShowTextEditor(false)}
                  className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                  取消
                </button>
                <button
                  onClick={addTextContent}
                  className="rounded-md border border-transparent bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-indigo-700"
                >
                  添加
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ProgramEditor;
