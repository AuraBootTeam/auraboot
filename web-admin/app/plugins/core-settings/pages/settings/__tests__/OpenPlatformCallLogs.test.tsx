import { act, fireEvent, render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import OpenPlatformCallLogs from '../OpenPlatformCallLogs';

vi.mock('~/contexts/I18nContext', () => ({useI18n: () => ({locale:'en-US',t:(_key:string,_params:unknown,fallback:string)=>fallback})}));
vi.mock('~/hooks/useFormatters', () => ({useDateFormat:()=>({formatDate:()=> '2026-11-24 12:00:00'})}));
vi.mock('~/contexts/ToastContext',()=>({useToastContext:()=>({showToast:vi.fn()})}));
const api=vi.hoisted(()=>vi.fn());
vi.mock('../open-platform-api',()=>({openPlatformApiFetch:api,OpenPlatformApiError:class extends Error{status=500;}}));
afterEach(()=>{cleanup();vi.useRealTimers();api.mockReset();});

describe('Call-log pagination stability',()=>{
 it('does not reset an immediately selected second page when the initial debounce interval elapses',async()=>{
   vi.useFakeTimers();
   const row=(index:number)=>({auditPid:`audit-${index}`,requestId:`request-${index}`,method:'GET',path:'/api/open/v1/whoami',status:200,durationMs:10,occurredAt:'2026-11-24T12:00:00Z'});
   api.mockImplementation(async(url:string)=>url.includes('/overview?')?{windowHours:24,totalCalls:26,errorCalls:0,errorRate:0,p95DurationMs:10}:url.includes('offset=25')?[row(26)]:Array.from({length:26},(_,index)=>row(index+1)));
   await act(async()=>{render(<OpenPlatformCallLogs installationPid="installation-a" applicationName="Owned application" environment="Development" canReadPayload/>);});
   expect(screen.getByRole('button',{name:'View call request-1',exact:true})).toBeInTheDocument();
   await act(async()=>{fireEvent.click(screen.getByRole('button',{name:'Next page',exact:true}));});
   expect(screen.getByRole('button',{name:'View call request-26',exact:true})).toBeInTheDocument();
   await act(async()=>{await vi.advanceTimersByTimeAsync(500);});
   expect(screen.getByRole('button',{name:'View call request-26',exact:true})).toBeInTheDocument();
   expect(screen.queryByRole('button',{name:'View call request-1',exact:true})).toBeNull();
   expect(api.mock.calls.filter(([url])=>String(url).includes('/audits?')&&String(url).includes('offset=0'))).toHaveLength(1);
 });
});
