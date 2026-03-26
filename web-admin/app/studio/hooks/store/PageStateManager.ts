export interface ValidationRule {
  field: string;
  validator: (value: any) => boolean;
  message: string;
}

export interface ComponentState {
  id?: string;
  type: string;
  props: Record<string, any>;
  validation?: {
    required?: string[];
    rules?: ValidationRule[];
  };
}

export interface PageState {
  components: Record<string, ComponentState>;
}

export interface StateChangeEvent {
  type: string;
  timestamp: number;
  payload?: any;
}

export class PageStateManager {
  private state: PageState = { components: {} };

  getState(): PageState {
    return this.state;
  }

  getHistory() {
    return { canUndo: false, canRedo: false, size: 0 };
  }

  on(_event: string, _handler: (event: StateChangeEvent) => void): () => void {
    return () => {};
  }

  off(_event: string, _handler: (event: StateChangeEvent) => void): void {}

  undo(): void {}

  redo(): void {}

  clearHistory(): void {}

  batchUpdateComponents(
    _updates: Array<{ componentId: string; state: Partial<ComponentState> }>,
    _source?: string,
  ): void {}

  removeComponentState(_componentId: string, _source?: string): void {}

  serialize(): string {
    return JSON.stringify(this.state);
  }

  deserialize(serialized: string): void {
    try {
      this.state = JSON.parse(serialized) as PageState;
    } catch {
      this.state = { components: {} };
    }
  }

  setState(state: PageState, _source?: string): void {
    this.state = state;
  }
}
