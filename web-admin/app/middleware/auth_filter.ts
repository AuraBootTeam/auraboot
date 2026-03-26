import { unstable_createContext } from 'react-router';
import type { Session, unstable_RouterContextProvider } from 'react-router';
import { createSessionMiddleware } from '~/middleware/sessionMiddlewareFactory';

const sessionContext = unstable_createContext<Session>();

// 使用工厂函数创建中间件
export const sessionMiddleware = createSessionMiddleware();

export function getSession1(context: unstable_RouterContextProvider) {
  return context.get(sessionContext);
}
