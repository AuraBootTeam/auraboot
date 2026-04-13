import React, { Component } from 'react';
import type { ReactNode } from 'react';

interface BlockErrorBoundaryProps {
  blockType: string;
  blockId?: string;
  children: ReactNode;
}

interface BlockErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

export class BlockErrorBoundary extends Component<
  BlockErrorBoundaryProps,
  BlockErrorBoundaryState
> {
  constructor(props: BlockErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): BlockErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    console.error(`[BlockErrorBoundary] Block "${this.props.blockType}" crashed:`, error, info);
  }

  handleRetry = (): void => {
    this.setState({ hasError: false, error: null });
  };

  render(): ReactNode {
    if (this.state.hasError) {
      return (
        <div
          className="rounded-lg border border-red-200 bg-red-50 p-4"
          data-testid="block-error-boundary"
        >
          <div className="flex items-start gap-3">
            <div className="flex-shrink-0 text-red-500">
              <svg className="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path
                  fillRule="evenodd"
                  d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.28 7.22a.75.75 0 00-1.06 1.06L8.94 10l-1.72 1.72a.75.75 0 101.06 1.06L10 11.06l1.72 1.72a.75.75 0 101.06-1.06L11.06 10l1.72-1.72a.75.75 0 00-1.06-1.06L10 8.94 8.28 7.22z"
                  clipRule="evenodd"
                />
              </svg>
            </div>
            <div className="min-w-0 flex-1">
              <h3 className="text-sm font-medium text-red-800">Block render error</h3>
              <p className="mt-1 text-sm text-red-700">
                <code className="rounded bg-red-100 px-1 py-0.5 font-mono text-xs">
                  {this.props.blockType}
                </code>
                {this.props.blockId && (
                  <span className="ml-1 text-red-500">({this.props.blockId})</span>
                )}
              </p>
              {this.state.error && (
                <p className="mt-1 truncate font-mono text-xs text-red-600">
                  {this.state.error.message}
                </p>
              )}
            </div>
            <button
              onClick={this.handleRetry}
              className="flex-shrink-0 rounded-md bg-red-100 px-3 py-1.5 text-sm font-medium text-red-800 transition-colors hover:bg-red-200"
            >
              Retry
            </button>
            {/* Hidden testid for detection */}
            <span data-testid={`block-error-${this.props.blockType}`} className="hidden" />
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
