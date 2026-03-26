export type FeedbackType = 'bug' | 'feature' | 'improvement' | 'question';
export type FeedbackStatus = 'open' | 'acknowledged' | 'in_progress' | 'resolved' | 'closed';
export type FeedbackPriority = 'low' | 'medium' | 'high' | 'critical';

export interface FeedbackItem {
  id: number;
  pid: string;
  userId: number;
  userName: string;
  type: FeedbackType;
  title: string;
  description: string;
  status: FeedbackStatus;
  priority: FeedbackPriority;
  voteCount: number;
  votedByCurrentUser: boolean;
  commentCount: number;
  metadata: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface FeedbackComment {
  id: number;
  feedbackId: number;
  userId: number;
  userName: string;
  content: string;
  createdAt: string;
}

export interface CreateFeedbackRequest {
  type: FeedbackType;
  title: string;
  description?: string;
  priority?: FeedbackPriority;
  metadata?: string;
}

export interface CreateCommentRequest {
  content: string;
}
