import { renderHook, act, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

vi.mock('~/utils/type', () => ({
  ResultHelper: {
    isSuccess: (r: { code: string }) => r.code === '0',
  },
}));

import { useStoreForm, initialFormData } from '../useStoreForm';
import { fetchResult } from '~/shared/services/http-client';

const mockFetch = fetchResult as ReturnType<typeof vi.fn>;

const ok = (data: unknown) => ({ code: '0', data });
const err = (code = '1') => ({ code, data: null });

describe('useStoreForm', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // By default, provinces fetch returns empty
    mockFetch.mockResolvedValue(ok([]));
  });

  it('initial state matches initialFormData', async () => {
    const { result } = renderHook(() => useStoreForm());

    await waitFor(() => {
      expect(result.current.provincesLoading).toBe(false);
    });

    expect(result.current.formData).toEqual(initialFormData);
    expect(result.current.loading).toBe(false);
    expect(result.current.submitting).toBe(false);
    expect(result.current.errors).toEqual({});
    expect(result.current.toast.show).toBe(false);
  });

  it('fetches provinces on mount', async () => {
    const provinces = [
      { code: '11', name: '北京市' },
      { code: '31', name: '上海市' },
    ];
    mockFetch.mockResolvedValue(ok(provinces));

    const { result } = renderHook(() => useStoreForm());

    await waitFor(() => {
      expect(result.current.provincesLoading).toBe(false);
    });

    expect(result.current.provinces).toEqual(provinces);
    expect(mockFetch).toHaveBeenCalledWith(
      '/api/stores/address/provinces',
      expect.objectContaining({ method: 'get' }),
    );
  });

  it('sets provinces to [] when API returns non-array data', async () => {
    mockFetch.mockResolvedValue(ok({ weird: 'shape' }));

    const { result } = renderHook(() => useStoreForm());

    await waitFor(() => expect(result.current.provincesLoading).toBe(false));

    expect(result.current.provinces).toEqual([]);
  });

  it('loads store data when storeId provided', async () => {
    const storeData = {
      name: 'Test Store',
      code: 'S001',
      type: 'flagship',
      status: 'active',
      contactPhone: '13800138000',
      contactEmail: 'store@test.com',
      openDate: '2024-01-01',
      closeDate: '',
      description: 'A test store',
      businessHours: '9-21',
      address: {
        provinceCode: '11',
        provinceName: '北京市',
        cityCode: '1101',
        cityName: '北京市',
        districtCode: '110101',
        districtName: '东城区',
        streetCode: '',
        streetName: '',
        detailAddress: '王府井大街1号',
        postalCode: '100006',
      },
    };

    // First call: provinces. Second call: store data.
    mockFetch
      .mockResolvedValueOnce(ok([]))
      .mockResolvedValueOnce(ok(storeData));

    const { result } = renderHook(() => useStoreForm('store-1'));

    await waitFor(() => {
      expect(result.current.loading).toBe(false);
    });

    expect(result.current.formData.name).toBe('Test Store');
    expect(result.current.formData.code).toBe('S001');
    expect(result.current.formData.type).toBe('flagship');
    expect(result.current.formData.provinceCode).toBe('11');
    expect(result.current.formData.detailAddress).toBe('王府井大街1号');
  });

  it('handleInputChange updates formData field', async () => {
    const { result } = renderHook(() => useStoreForm());
    await waitFor(() => expect(result.current.provincesLoading).toBe(false));

    act(() => {
      result.current.handleInputChange('name', 'My Store');
    });

    expect(result.current.formData.name).toBe('My Store');
  });

  it('handleInputChange clears error for that field', async () => {
    const { result } = renderHook(() => useStoreForm());
    await waitFor(() => expect(result.current.provincesLoading).toBe(false));

    act(() => {
      result.current.setErrors({ name: 'required' });
    });
    expect(result.current.errors.name).toBe('required');

    act(() => {
      result.current.handleInputChange('name', 'Some Name');
    });

    expect(result.current.errors.name).toBe('');
  });

  it('handleExtensionChange updates extension field', async () => {
    const { result } = renderHook(() => useStoreForm());
    await waitFor(() => expect(result.current.provincesLoading).toBe(false));

    act(() => {
      result.current.handleExtensionChange('customField', 'value1');
    });

    expect(result.current.formData.extension?.customField).toBe('value1');
  });

  it('handleAddressChange updates address fields and clears address errors', async () => {
    const { result } = renderHook(() => useStoreForm());
    await waitFor(() => expect(result.current.provincesLoading).toBe(false));

    act(() => {
      result.current.setErrors({ provinceCode: 'required', cityCode: 'required' });
    });

    act(() => {
      result.current.handleAddressChange({
        provinceCode: '31',
        provinceName: '上海市',
        cityCode: '3101',
        cityName: '上海市',
        districtCode: '310101',
        districtName: '黄浦区',
        streetCode: '',
        streetName: '',
      });
    });

    expect(result.current.formData.provinceCode).toBe('31');
    expect(result.current.formData.cityCode).toBe('3101');
    expect(result.current.errors.provinceCode).toBe('');
    expect(result.current.errors.cityCode).toBe('');
  });

  it('showToast and hideToast manage toast state', async () => {
    const { result } = renderHook(() => useStoreForm());
    await waitFor(() => expect(result.current.provincesLoading).toBe(false));

    act(() => {
      result.current.showToast('Something went wrong', 'error');
    });

    expect(result.current.toast.show).toBe(true);
    expect(result.current.toast.message).toBe('Something went wrong');
    expect(result.current.toast.type).toBe('error');

    act(() => {
      result.current.hideToast();
    });

    expect(result.current.toast.show).toBe(false);
  });
});
