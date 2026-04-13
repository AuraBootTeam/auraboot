import { useState, useEffect } from 'react';
import { ChevronDownIcon } from '@heroicons/react/24/outline';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

interface AddressData {
  code: string;
  name: string;
}

interface AddressValue {
  provinceCode?: string;
  provinceName?: string;
  cityCode?: string;
  cityName?: string;
  districtCode?: string;
  districtName?: string;
  streetCode?: string;
  streetName?: string;
}

interface AddressSelectorProps {
  value: AddressValue;
  onChange: (address: AddressValue) => void;
  disabled?: boolean;
  provinces: AddressData[];
  errors?: {
    provinceCode?: string;
    cityCode?: string;
    districtCode?: string;
  };
}

export default function AddressSelector({
  value,
  onChange,
  disabled = false,
  provinces,
  errors = {},
}: AddressSelectorProps) {
  const [cities, setCities] = useState<AddressData[]>([]);
  const [districts, setDistricts] = useState<AddressData[]>([]);
  const [streets, setStreets] = useState<AddressData[]>([]);
  const [loading, setLoading] = useState({
    cities: false,
    districts: false,
    streets: false,
  });

  // 确保provinces是数组
  const safeProvinces = Array.isArray(provinces) ? provinces : [];

  // 获取城市数据
  const fetchCities = async (provinceCode: string) => {
    if (!provinceCode) {
      setCities([]);
      return;
    }

    setLoading((prev) => ({ ...prev, cities: true }));
    try {
      const result = await fetchResult('/api/stores/address/cities', {
        method: 'get',
        params: { provinceCode },
      });

      if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
        setCities(result.data);
      } else {
        setCities([]);
      }
    } catch (error) {
      console.error('获取城市数据失败:', error);
      setCities([]);
    } finally {
      setLoading((prev) => ({ ...prev, cities: false }));
    }
  };

  // 获取区县数据
  const fetchDistricts = async (cityCode: string) => {
    if (!cityCode) {
      setDistricts([]);
      return;
    }

    setLoading((prev) => ({ ...prev, districts: true }));
    try {
      const result = await fetchResult('/api/stores/address/districts', {
        method: 'get',
        params: { cityCode },
      });

      if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
        setDistricts(result.data);
      } else {
        setDistricts([]);
      }
    } catch (error) {
      console.error('获取区县数据失败:', error);
      setDistricts([]);
    } finally {
      setLoading((prev) => ({ ...prev, districts: false }));
    }
  };

  // 获取街道数据
  const fetchStreets = async (districtCode: string) => {
    if (!districtCode) {
      setStreets([]);
      return;
    }

    setLoading((prev) => ({ ...prev, streets: true }));
    try {
      const result = await fetchResult('/api/stores/address/streets', {
        method: 'get',
        params: { districtCode },
      });

      if (ResultHelper.isSuccess(result) && Array.isArray(result.data)) {
        setStreets(result.data);
      } else {
        setStreets([]);
      }
    } catch (error) {
      console.error('获取街道数据失败:', error);
      setStreets([]);
    } finally {
      setLoading((prev) => ({ ...prev, streets: false }));
    }
  };

  // 当省份改变时获取城市数据
  useEffect(() => {
    if (value.provinceCode) {
      fetchCities(value.provinceCode);
    } else {
      setCities([]);
      setDistricts([]);
      setStreets([]);
    }
  }, [value.provinceCode]);

  // 当城市改变时获取区县数据
  useEffect(() => {
    if (value.cityCode) {
      fetchDistricts(value.cityCode);
    } else {
      setDistricts([]);
      setStreets([]);
    }
  }, [value.cityCode]);

  // 当区县改变时获取街道数据
  useEffect(() => {
    if (value.districtCode) {
      fetchStreets(value.districtCode);
    } else {
      setStreets([]);
    }
  }, [value.districtCode]);

  const handleProvinceChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selectedProvince = safeProvinces.find((p) => p.code === e.target.value);
    onChange({
      ...value,
      provinceCode: selectedProvince?.code || '',
      provinceName: selectedProvince?.name || '',
      cityCode: '',
      cityName: '',
      districtCode: '',
      districtName: '',
      streetCode: '',
      streetName: '',
    });
  };

  const handleCityChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selectedCity = cities.find((c) => c.code === e.target.value);
    onChange({
      ...value,
      cityCode: selectedCity?.code || '',
      cityName: selectedCity?.name || '',
      districtCode: '',
      districtName: '',
      streetCode: '',
      streetName: '',
    });
  };

  const handleDistrictChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selectedDistrict = districts.find((d) => d.code === e.target.value);
    onChange({
      ...value,
      districtCode: selectedDistrict?.code || '',
      districtName: selectedDistrict?.name || '',
      streetCode: '',
      streetName: '',
    });
  };

  const handleStreetChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    const selectedStreet = streets.find((s) => s.code === e.target.value);
    onChange({
      ...value,
      streetCode: selectedStreet?.code || '',
      streetName: selectedStreet?.name || '',
    });
  };

  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
      {/* 省份选择 */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          省份 <span className="text-red-500">*</span>
        </label>
        <div className="relative">
          <select
            value={value.provinceCode || ''}
            onChange={handleProvinceChange}
            disabled={disabled}
            className={`w-full appearance-none rounded-lg border bg-white px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:bg-gray-50 ${
              errors.provinceCode ? 'border-red-300' : 'border-gray-300'
            }`}
          >
            <option value="">请选择省份</option>
            {safeProvinces.map((province) => (
              <option key={province.code} value={province.code}>
                {province.name}
              </option>
            ))}
          </select>
          <ChevronDownIcon className="pointer-events-none absolute top-2.5 right-3 h-5 w-5 text-gray-400" />
        </div>
        {errors.provinceCode && <p className="mt-1 text-sm text-red-600">{errors.provinceCode}</p>}
      </div>

      {/* 城市选择 */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          城市 <span className="text-red-500">*</span>
        </label>
        <div className="relative">
          <select
            value={value.cityCode || ''}
            onChange={handleCityChange}
            disabled={disabled || !value.provinceCode || loading.cities}
            className={`w-full appearance-none rounded-lg border bg-white px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:bg-gray-50 ${
              errors.cityCode ? 'border-red-300' : 'border-gray-300'
            }`}
          >
            <option value="">{loading.cities ? '加载中...' : '请选择城市'}</option>
            {cities.map((city) => (
              <option key={city.code} value={city.code}>
                {city.name}
              </option>
            ))}
          </select>
          <ChevronDownIcon className="pointer-events-none absolute top-2.5 right-3 h-5 w-5 text-gray-400" />
        </div>
        {errors.cityCode && <p className="mt-1 text-sm text-red-600">{errors.cityCode}</p>}
      </div>

      {/* 区县选择 */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">
          区县 <span className="text-red-500">*</span>
        </label>
        <div className="relative">
          <select
            value={value.districtCode || ''}
            onChange={handleDistrictChange}
            disabled={disabled || !value.cityCode || loading.districts}
            className={`w-full appearance-none rounded-lg border bg-white px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:bg-gray-50 ${
              errors.districtCode ? 'border-red-300' : 'border-gray-300'
            }`}
          >
            <option value="">{loading.districts ? '加载中...' : '请选择区县'}</option>
            {districts.map((district) => (
              <option key={district.code} value={district.code}>
                {district.name}
              </option>
            ))}
          </select>
          <ChevronDownIcon className="pointer-events-none absolute top-2.5 right-3 h-5 w-5 text-gray-400" />
        </div>
        {errors.districtCode && <p className="mt-1 text-sm text-red-600">{errors.districtCode}</p>}
      </div>

      {/* 街道选择 */}
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700">街道</label>
        <div className="relative">
          <select
            value={value.streetCode || ''}
            onChange={handleStreetChange}
            disabled={disabled || !value.districtCode || loading.streets}
            className="w-full appearance-none rounded-lg border border-gray-300 bg-white px-3 py-2 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:bg-gray-50"
          >
            <option value="">{loading.streets ? '加载中...' : '请选择街道'}</option>
            {streets.map((street) => (
              <option key={street.code} value={street.code}>
                {street.name}
              </option>
            ))}
          </select>
          <ChevronDownIcon className="pointer-events-none absolute top-2.5 right-3 h-5 w-5 text-gray-400" />
        </div>
      </div>
    </div>
  );
}
