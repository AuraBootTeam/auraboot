export interface AdminDocument {
  id?: number;
  document_id: string;
  tenant_id?: string;
  title?: string;
  source?: string;
  document_type?: string;
  symbol?: string;
  broker?: string;
  publish_date?: string;
  file_size?: number;
  status?: string;
  total_chunks?: number;
  total_tokens?: number;
  uploaded_by?: string;
  approval_status?: string;
  approved_by?: string;
  approved_at?: string;
  admin_notes?: string;
  quality_score?: number;
  created_at?: string;
  updated_at?: string;
}

export interface Citation {
  id: number;
  document_type: string;
  source: string;
  similarity?: number;
  page?: number;
  text?: string;
  pdf_url?: string;
}

export interface ToolStatus {
  tool_name: string;
  status: 'running' | 'completed' | 'failed';
  message?: string;
  result?: any;
  error?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: Date;
  thinkingProcess?: string;
  citations?: Citation[];
  toolStatuses?: ToolStatus[];
  error?: string;
}

export interface ChatRequest {
  sessionId: string;
  message: string;
  stream?: boolean;
}

export interface SSEEvent {
  type: 'text_delta' | 'reasoning_delta' | 'citation' | 'tool_status' | 'error' | 'done';
  data: any;
}
