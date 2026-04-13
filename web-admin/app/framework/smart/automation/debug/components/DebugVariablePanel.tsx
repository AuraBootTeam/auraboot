/**
 * DebugVariablePanel - Tree display of execution context variables.
 */

import React, { useState } from 'react';
import { cn } from '~/utils/cn';
import { useDebugSession } from '../hooks/useDebugSession';

function JsonTree({ data, depth = 0 }: { data: unknown; depth?: number }) {
  const [expanded, setExpanded] = useState(depth < 2);

  if (data === null || data === undefined) {
    return <span className="text-gray-400 italic">null</span>;
  }

  if (typeof data === 'string') {
    return <span className="text-green-600">"{data}"</span>;
  }

  if (typeof data === 'number' || typeof data === 'boolean') {
    return <span className="text-blue-600">{String(data)}</span>;
  }

  if (Array.isArray(data)) {
    if (data.length === 0) return <span className="text-gray-400">[]</span>;
    return (
      <div>
        <button
          onClick={() => setExpanded(!expanded)}
          className="text-gray-500 hover:text-gray-700"
        >
          {expanded ? '▼' : '▶'} Array[{data.length}]
        </button>
        {expanded && (
          <div className="ml-4 border-l border-gray-200 pl-2">
            {data.map((item, i) => (
              <div key={i} className="py-0.5">
                <span className="mr-1 text-xs text-gray-400">{i}:</span>
                <JsonTree data={item} depth={depth + 1} />
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  if (typeof data === 'object') {
    const entries = Object.entries(data as Record<string, unknown>);
    if (entries.length === 0) return <span className="text-gray-400">{'{}'}</span>;
    return (
      <div>
        <button
          onClick={() => setExpanded(!expanded)}
          className="text-gray-500 hover:text-gray-700"
        >
          {expanded ? '▼' : '▶'} Object{`{${entries.length}}`}
        </button>
        {expanded && (
          <div className="ml-4 border-l border-gray-200 pl-2">
            {entries.map(([key, value]) => (
              <div key={key} className="py-0.5">
                <span className="mr-1 text-xs font-medium text-purple-600">{key}:</span>
                <JsonTree data={value} depth={depth + 1} />
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  return <span className="text-gray-500">{String(data)}</span>;
}

export function DebugVariablePanel() {
  const session = useDebugSession((s) => s.session);

  if (!session) return null;

  const context = session.executionContext || {};

  return (
    <div className="flex h-full flex-col">
      <div className="border-b bg-gray-50 px-3 py-2 text-xs font-medium tracking-wide text-gray-600 uppercase">
        Variables
      </div>
      <div className="flex-1 overflow-auto p-3 font-mono text-xs">
        {Object.keys(context).length === 0 ? (
          <p className="py-4 text-center text-gray-400">No variables yet</p>
        ) : (
          <JsonTree data={context} />
        )}
      </div>
    </div>
  );
}
