import React, { useState } from 'react';

interface ImageDisplayProps {
  name?: string;
  value?: string | string[];
  label?: string;
  preview?: boolean;
  className?: string;
  readOnly?: boolean;
  disabled?: boolean;
  [key: string]: any;
}

const ImageDisplay: React.FC<ImageDisplayProps> = ({
  value,
  label,
  preview = true,
  className = '',
}) => {
  const [previewImage, setPreviewImage] = useState<string | null>(null);

  // 处理图片数据
  const getImageUrls = (): string[] => {
    if (!value) return [];

    if (typeof value === 'string') {
      try {
        // 尝试解析JSON字符串
        const parsed = JSON.parse(value);
        if (Array.isArray(parsed)) {
          return parsed;
        }
        return [value];
      } catch {
        // 如果不是JSON，当作单个URL处理
        return [value];
      }
    }

    if (Array.isArray(value)) {
      return value;
    }

    return [];
  };

  const imageUrls = getImageUrls();

  // 打开预览
  const handlePreview = (url: string) => {
    if (preview) {
      setPreviewImage(url);
    }
  };

  // 关闭预览
  const handleClosePreview = () => {
    setPreviewImage(null);
  };

  if (imageUrls.length === 0) {
    return (
      <div className={`space-y-1 ${className}`}>
        {label && <label className="block text-sm font-medium text-gray-700">{label}</label>}
        <div className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-500">
          暂无图片
        </div>
      </div>
    );
  }

  return (
    <div className={`space-y-1 ${className}`}>
      {label && <label className="block text-sm font-medium text-gray-700">{label}</label>}

      <div className="flex flex-wrap gap-2">
        {imageUrls.map((url, index) => (
          <div
            key={index}
            className="group relative cursor-pointer"
            onClick={() => handlePreview(url)}
          >
            <img
              src={url}
              alt={`图片 ${index + 1}`}
              className="h-20 w-20 rounded-md border border-gray-200 object-cover transition-colors hover:border-blue-400"
              onError={(e) => {
                const target = e.target as HTMLImageElement;
                target.src =
                  'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iODAiIGhlaWdodD0iODAiIHZpZXdCb3g9IjAgMCA4MCA4MCIgZmlsbD0ibm9uZSIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj4KPHJlY3Qgd2lkdGg9IjgwIiBoZWlnaHQ9IjgwIiBmaWxsPSIjRjNGNEY2Ii8+CjxwYXRoIGQ9Ik0yNCAzMkM0NC40IDMyIDQ4IDM1LjYgNDggNTZDNDggNzYuNCA0NC40IDgwIDI0IDgwQzMuNiA4MCAzIDc2LjQgMCA1NkMwIDM1LjYgMy42IDMyIDI0IDMyWiIgZmlsbD0iI0U1RTdFQiIvPgo8cGF0aCBkPSJNMzIgNDBIMTZWNTZIMzJWNDBaIiBmaWxsPSIjOUI5Q0EwIi8+CjxwYXRoIGQ9Ik0yNCA0NEMyNi4yIDQ0IDI4IDQ1LjggMjggNDhDMjggNTAuMiAyNi4yIDUyIDI0IDUyQzIxLjggNTIgMjAgNTAuMiAyMCA0OEMyMCA0NS44IDIxLjggNDQgMjQgNDRaIiBmaWxsPSIjOUI5Q0EwIi8+Cjwvc3ZnPgo=';
              }}
            />
            {preview && (
              <div className="bg-opacity-0 group-hover:bg-opacity-20 absolute inset-0 flex items-center justify-center rounded-md bg-black transition-all">
                <svg
                  className="h-6 w-6 text-white opacity-0 transition-opacity group-hover:opacity-100"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                  />
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                  />
                </svg>
              </div>
            )}
          </div>
        ))}
      </div>

      {/* 图片预览模态框 */}
      {previewImage && (
        <div
          className="bg-opacity-75 fixed inset-0 z-50 flex items-center justify-center bg-black"
          onClick={handleClosePreview}
        >
          <div className="max-h-4xl relative max-w-4xl p-4">
            <img
              src={previewImage}
              alt="预览图片"
              className="max-h-full max-w-full object-contain"
            />
            <button
              onClick={handleClosePreview}
              className="bg-opacity-50 hover:bg-opacity-75 absolute top-2 right-2 rounded-full bg-black p-2 text-white transition-colors"
            >
              <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        </div>
      )}
    </div>
  );
};

export default ImageDisplay;
