import { createContext } from 'react-router';
import type { Session, RouterContextProvider } from 'react-router';
import { createSessionMiddleware } from '~/middleware/sessionMiddlewareFactory';

const sessionContext = createContext<Session>();

// 使用工厂函数创建中间件
export const sessionMiddleware = createSessionMiddleware();

export function getSession1(context: RouterContextProvider) {
  return context.get(sessionContext);
}
