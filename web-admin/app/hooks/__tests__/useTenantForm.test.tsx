import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// useTenantForm uses fetchResult internally in components but the hook itself
// only manages state — no async fetch is called from within the hook itself.
// fetchResult is only called by the page components that USE this hook.
// We test the hook's pure state transitions here.

import {
  useTenantForm,
  initialTenantFormData,
  getIndustryLabel,
  getIndustryValue,
  industryOptions,
} from '../useTenantForm';

describe('getIndustryLabel', () => {
  it('returns label for known value', () => {
    expect(getIndustryLabel('retail')).toBe('零售业');
    expect(getIndustryLabel('finance')).toBe('金融服务');
  });

  it('returns value itself for unknown code', () => {
    expect(getIndustryLabel('unknown_industry')).toBe('unknown_industry');
  });

  it('returns empty string label for empty value', () => {
    expect(getIndustryLabel('')).toBe('请选择行业');
  });
});

describe('getIndustryValue', () => {
  it('returns value for known label', () => {
    expect(getIndustryValue('零售业')).toBe('retail');
    expect(getIndustryValue('制造业')).toBe('manufacturing');
  });

  it('returns label itself for unknown label', () => {
    expect(getIndustryValue('未知行业')).toBe('未知行业');
  });
});

describe('industryOptions', () => {
  it('contains at least one entry with empty value as placeholder', () => {
    const placeholder = industryOptions.find((o) => o.value === '');
    expect(placeholder).toBeDefined();
  });

  it('has more than 5 options', () => {
    expect(industryOptions.length).toBeGreaterThan(5);
  });
});

describe('useTenantForm', () => {
  it('initial state matches initialTenantFormData', () => {
    const { result } = renderHook(() => useTenantForm());
    expect(result.current.formData).toEqual(initialTenantFormData);
    expect(result.current.loading).toBe(true); // initial loading=true
    expect(result.current.submitting).toBe(false);
    expect(result.current.errors).toEqual({});
    expect(result.current.tenantPid).toBe('');
  });

  it('handleInputChange updates formData field', () => {
    const { result } = renderHook(() => useTenantForm());
    act(() => {
      result.current.handleInputChange({
        target: { name: 'displayName', value: 'Acme Corp' },
      } as React.ChangeEvent<HTMLInputElement>);
    });
    expect(result.current.formData.displayName).toBe('Acme Corp');
  });

  it('handleInputChange clears error for the changed field', () => {
    const { result } = renderHook(() => useTenantForm());
    act(() => {
      result.current.setErrors({ website: 'invalid url' });
    });
    expect(result.current.errors.website).toBe('invalid url');

    act(() => {
      result.current.handleInputChange({
        target: { name: 'website', value: 'https://valid.com' },
      } as React.ChangeEvent<HTMLInputElement>);
    });
    expect(result.current.errors.website).toBe('');
  });

  describe('validateForm', () => {
    it('returns true when form is valid', () => {
      const { result } = renderHook(() => useTenantForm());
      act(() => {
        result.current.handleInputChange({
          target: { name: 'website', value: 'https://acme.com' },
        } as React.ChangeEvent<HTMLInputElement>);
      });
      let valid = false;
      act(() => {
        valid = result.current.validateForm();
      });
      expect(valid).toBe(true);
      expect(result.current.errors).toEqual({});
    });

    it('returns true when website is empty (no validation required)', () => {
      const { result } = renderHook(() => useTenantForm());
      let valid = false;
      act(() => {
        valid = result.current.validateForm();
      });
      expect(valid).toBe(true);
    });

    it('returns false and sets error for invalid website', () => {
      const { result } = renderHook(() => useTenantForm());
      act(() => {
        result.current.handleInputChange({
          target: { name: 'website', value: 'not-a-url' },
        } as React.ChangeEvent<HTMLInputElement>);
      });
      let valid = true;
      act(() => {
        valid = result.current.validateForm();
      });
      expect(valid).toBe(false);
      expect(result.current.errors.website).toBeTruthy();
    });
  });

  it('resetForm restores initial state', () => {
    const { result } = renderHook(() => useTenantForm());
    act(() => {
      result.current.handleInputChange({
        target: { name: 'displayName', value: 'Changed' },
      } as React.ChangeEvent<HTMLInputElement>);
      result.current.setErrors({ website: 'bad' });
      result.current.setTenantPid('tenant-123');
    });

    act(() => {
      result.current.resetForm();
    });

    expect(result.current.formData).toEqual(initialTenantFormData);
    expect(result.current.errors).toEqual({});
    expect(result.current.tenantPid).toBe('');
  });

  it('setTenantFormData populates form from TenantInfo', () => {
    const { result } = renderHook(() => useTenantForm());
    act(() => {
      result.current.setTenantFormData({
        id: 1,
        pid: 'pid-001',
        name: 'acme',
        displayName: 'Acme Corp',
        logo: 'https://logo.png',
        industry: '零售业', // label → should be converted to value 'retail'
        contactEmail: 'admin@acme.com',
        contactPhone: '12345678',
        website: 'https://acme.com',
        status: 'active',
        description: 'A company',
        createdAt: '2024-01-01',
        updatedAt: '2024-01-02',
      });
    });

    expect(result.current.tenantPid).toBe('pid-001');
    expect(result.current.formData.name).toBe('acme');
    expect(result.current.formData.displayName).toBe('Acme Corp');
    expect(result.current.formData.industry).toBe('retail');
    expect(result.current.formData.contactEmail).toBe('admin@acme.com');
    expect(result.current.formData.website).toBe('https://acme.com');
  });
});
