import React, { useState } from 'react';
import { MapPin, Search } from 'lucide-react';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/components/ui/field-base';

interface Coordinates {
  latitude: number;
  longitude: number;
  address?: string;
}

interface CoordinatesPickerProps {
  name: string;
  label?: string;
  placeholder?: string;
  value?: Coordinates;
  onChange?: (value: Coordinates | undefined) => void;
  disabled?: boolean;
  required?: boolean;
  mapType?: 'amap' | 'google' | 'baidu';
  defaultZoom?: number;
  className?: string;
}

export const CoordinatesPicker: React.FC<CoordinatesPickerProps> = ({
  name,
  label,
  placeholder = '请选择地理坐标',
  value,
  onChange,
  disabled = false,
  required = false,
  mapType = 'amap',
  defaultZoom = 15,
  className = '',
}) => {
  const st = useSmartText();
  const {
    labelText,
    placeholderText,
    required: requiredValue,
    disabled: disabledValue,
  } = useSmartFieldContract({
    label,
    placeholder,
    required,
    disabled,
  });
  const meta = useSmartFieldMeta({ externalError: undefined });
  const [isMapOpen, setIsMapOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  const handleCoordinateSelect = (lat: number, lng: number, address?: string) => {
    const coordinates: Coordinates = {
      latitude: lat,
      longitude: lng,
      address,
    };
    onChange?.(coordinates);
    setIsMapOpen(false);
    meta.markTouched();
  };

  const handleClear = () => {
    onChange?.(undefined);
    meta.markTouched();
  };

  const formatCoordinates = (coords: Coordinates) => {
    return `${coords.latitude.toFixed(6)}, ${coords.longitude.toFixed(6)}${coords.address ? ` (${coords.address})` : ''}`;
  };

  return (
    <FieldBase
      id={name}
      label={labelText}
      required={requiredValue}
      error={meta.showError ? st(meta.meta.error) : undefined}
      className={`space-y-2 ${className}`}
    >
      <div className="relative">
        <div
          className={`w-full rounded-md border border-gray-300 px-3 py-2 shadow-sm ${disabledValue ? 'cursor-not-allowed bg-gray-50' : 'cursor-pointer bg-white hover:border-gray-400'} ${value ? 'text-gray-900' : 'text-gray-500'} focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none`}
          onClick={() => !disabledValue && setIsMapOpen(true)}
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center space-x-2">
              <MapPin className="h-4 w-4 text-gray-400" />
              <span className="truncate">{value ? formatCoordinates(value) : placeholderText}</span>
            </div>
            {value && !disabledValue && (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  handleClear();
                }}
                className="text-gray-400 hover:text-gray-600"
              >
                ×
              </button>
            )}
          </div>
        </div>
      </div>

      {/* Mock Map Modal */}
      {isMapOpen && (
        <div className="bg-opacity-50 fixed inset-0 z-50 flex items-center justify-center bg-black">
          <div className="max-h-[80vh] w-full max-w-4xl overflow-hidden rounded-lg bg-white p-6">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-lg font-semibold">{st('选择地理坐标')}</h3>
              <button
                onClick={() => setIsMapOpen(false)}
                className="text-gray-400 hover:text-gray-600"
              >
                ×
              </button>
            </div>

            {/* Search Bar */}
            <div className="mb-4">
              <div className="relative">
                <Search className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 transform text-gray-400" />
                <input
                  type="text"
                  placeholder={st('搜索地址或地点')}
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="w-full rounded-md border border-gray-300 py-2 pr-4 pl-10 focus:ring-2 focus:ring-blue-500 focus:outline-none"
                />
              </div>
            </div>

            {/* Mock Map Area */}
            <div className="mb-4 flex h-96 items-center justify-center rounded-lg bg-gray-100">
              <div className="text-center">
                <MapPin className="mx-auto mb-2 h-12 w-12 text-gray-400" />
                <p className="mb-4 text-gray-500">
                  {st('地图组件占位符')} ({mapType.toUpperCase()})
                </p>
                <p className="mb-4 text-sm text-gray-400">
                  {st('缩放级别')}: {defaultZoom}
                </p>

                {/* Mock Location Options */}
                <div className="space-y-2">
                  <button
                    onClick={() => handleCoordinateSelect(39.9042, 116.4074, '北京市天安门广场')}
                    className="block w-full rounded border bg-white px-4 py-2 text-left hover:bg-gray-50"
                  >
                    <div className="font-medium">{st('北京市天安门广场')}</div>
                    <div className="text-sm text-gray-500">39.9042, 116.4074</div>
                  </button>
                  <button
                    onClick={() => handleCoordinateSelect(31.2304, 121.4737, '上海市外滩')}
                    className="block w-full rounded border bg-white px-4 py-2 text-left hover:bg-gray-50"
                  >
                    <div className="font-medium">{st('上海市外滩')}</div>
                    <div className="text-sm text-gray-500">31.2304, 121.4737</div>
                  </button>
                  <button
                    onClick={() => handleCoordinateSelect(22.3193, 114.1694, '深圳市市民中心')}
                    className="block w-full rounded border bg-white px-4 py-2 text-left hover:bg-gray-50"
                  >
                    <div className="font-medium">{st('深圳市市民中心')}</div>
                    <div className="text-sm text-gray-500">22.3193, 114.1694</div>
                  </button>
                </div>
              </div>
            </div>

            <div className="flex justify-end space-x-2">
              <button
                onClick={() => setIsMapOpen(false)}
                className="rounded-md border border-gray-300 px-4 py-2 text-gray-600 hover:bg-gray-50"
              >
                {st('取消')}
              </button>
              <button
                onClick={() => handleCoordinateSelect(39.9042, 116.4074, '默认位置')}
                className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
              >
                {st('确定')}
              </button>
            </div>
          </div>
        </div>
      )}

      <input type="hidden" name={name} value={value ? JSON.stringify(value) : ''} />
    </FieldBase>
  );
};

export default CoordinatesPicker;
