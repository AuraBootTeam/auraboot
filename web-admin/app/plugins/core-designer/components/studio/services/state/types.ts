export interface ComponentState {
  type: string;
  props: Record<string, any>;
  internalState: Record<string, any>;
  styles: Record<string, any>;
  isVisible: boolean;
  isSelected: boolean;
  isHovered: boolean;
  isDragging: boolean;
  validation: {
    isValid: boolean;
    errors: string[];
    warnings: string[];
  };
  loading: {
    isLoading: boolean;
    loadingText: string;
  };
  error: any | null;
  metadata: {
    createdAt: Date;
    updatedAt: Date;
  };
  [key: string]: any;
}

export interface PageInfo {
  id: string;
  title: string;
  description: string;
  version: string;
  createdAt: Date;
  updatedAt: Date;
  [key: string]: any;
}

export interface UIState {
  selectedComponentId: string | null;
  hoveredComponentId: string | null;
  draggedComponentId: string | null;
  isPreviewMode: boolean;
  zoom: number;
  viewport: { width: number; height: number };
  showGrid: boolean;
  showRuler: boolean;
  showOutline: boolean;
  [key: string]: any;
}

export interface UserState {
  preferences: Record<string, any>;
  permissions: string[];
  role: string;
  [key: string]: any;
}

export interface EnvironmentState {
  mode: string;
  theme: string;
  locale: string;
  device: string;
  [key: string]: any;
}

export interface PageState {
  pageInfo: PageInfo;
  components: Record<string, ComponentState>;
  globalState: Record<string, any>;
  formData: Record<string, any>;
  uiState: UIState;
  userState: UserState;
  environment: EnvironmentState;
  temporaryState: Record<string, any>;
  // For compatibility with PageStateManager usage
  lastModified?: number;
  schema?: any; // Added for PageStateManager implementation compatibility
  selectedComponents?: string[];
  clipboard?: any[];
  isDirty?: boolean;
  isLoading?: boolean;
  error?: any;
  [key: string]: any;
}

export interface StateChange {
  type: string;
  path?: string;
  value?: any;
  oldValue?: any;
  timestamp?: number;
}

export interface StateHistory {
  snapshots: StateSnapshot[];
  currentIndex: number;
  maxSize: number;
}

export interface StateSnapshot {
  id: string;
  state: PageState;
  timestamp: number;
  description: string;
}

export interface StateSubscriptionOptions {
  deep?: boolean;
  immediate?: boolean;
}

export type StateSelector<T> = (state: PageState) => T;
export type StateUpdater<T> = (prevState: T) => T;
export type StateChangeEvent = StateChange;
