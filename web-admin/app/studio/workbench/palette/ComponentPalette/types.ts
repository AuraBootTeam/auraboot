export interface ComponentType {
  type: string;
  name: string;
  icon: string;
  category?: string;
  description?: string;
}

export interface ComponentCategory {
  id: string;
  name: string;
  icon: string;
  description: string;
  order: number;
}

export interface ComponentPaletteProps {
  componentTypes?: ComponentType[];
  categories?: ComponentCategory[];
  showCategories?: boolean;
  searchable?: boolean;
}
