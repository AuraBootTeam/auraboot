import type { RuntimeProfile } from '~/framework/runtime';

export interface ThemeSettingSchema {
  type: 'text' | 'textarea' | 'image' | 'color' | 'select' | 'checkbox' | 'number' | string;
  key: string;
  label?: string | Record<string, string>;
  defaultValue?: unknown;
  options?: Array<{
    label: string | Record<string, string>;
    value: string;
  }>;
}

export interface ThemeBlockSchema {
  type: string;
  label?: string | Record<string, string>;
  settings?: ThemeSettingSchema[];
}

export interface ThemeSectionSchema {
  type: string;
  componentName: string;
  label?: string | Record<string, string>;
  settings?: ThemeSettingSchema[];
  blocks?: ThemeBlockSchema[];
  runtimeProfiles?: RuntimeProfile[];
}

export interface ThemeTemplateSchema {
  type:
    | 'home'
    | 'collection'
    | 'product'
    | 'cart'
    | 'search'
    | 'account'
    | 'order'
    | 'checkout'
    | string;
  sections: Array<{
    id: string;
    type: string;
    settings?: Record<string, unknown>;
    blocks?: Array<{
      id: string;
      type: string;
      settings?: Record<string, unknown>;
    }>;
  }>;
}

export interface ThemeManifest {
  themeId: string;
  version: string;
  name: string | Record<string, string>;
  sections: ThemeSectionSchema[];
  templates: ThemeTemplateSchema[];
  assets?: Array<{
    key: string;
    url: string;
    contentType?: string;
  }>;
}
