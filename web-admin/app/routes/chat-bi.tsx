/**
 * ChatBI Route — natural language to chart query interface.
 */

import { ChatBIPanel } from '~/framework/smart/components/ai/ChatBIPanel';

export default function ChatBIPage() {
  return (
    <div className="h-[calc(100vh-64px)]">
      <ChatBIPanel />
    </div>
  );
}
