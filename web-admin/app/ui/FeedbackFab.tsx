import { useState } from 'react';
import { useNavigate } from 'react-router';

/**
 * Floating action button (FAB) for quick feedback submission.
 * Renders a fixed button in the bottom-right corner that links to the feedback page.
 */
export default function FeedbackFab() {
  const navigate = useNavigate();
  const [isHovered, setIsHovered] = useState(false);

  return (
    <button
      onClick={() => navigate('/feedback')}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
      className="fixed right-6 bottom-6 z-40 flex items-center gap-2 rounded-full bg-blue-600 px-4 py-3 text-white shadow-lg transition-all duration-200 hover:bg-blue-700 hover:shadow-xl"
      title="Send Feedback"
    >
      <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M8 10h.01M12 10h.01M16 10h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z"
        />
      </svg>
      {isHovered && <span className="text-sm font-medium whitespace-nowrap">Feedback</span>}
    </button>
  );
}
