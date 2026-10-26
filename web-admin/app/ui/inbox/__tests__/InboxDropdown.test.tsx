import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { InboxHeaderWidget } from '../InboxDropdown';

const state=vi.hoisted(()=>({listInboxItems:vi.fn(),markAllRead:vi.fn(),markRead:vi.fn(),t:(key:string)=>key}));
vi.mock('~/shared/services/inboxService',()=>state);
vi.mock('~/contexts/AuthContext',()=>({useAuth:()=>({user:{tenantId:1,pid:'reader'}})}));
vi.mock('~/contexts/I18nContext',()=>({useI18n:()=>({locale:'en-US',t:state.t})}));
vi.mock('~/contexts/TimezoneContext',()=>({useTimezone:()=>({timezone:'UTC'})}));
vi.mock('../InboxBadge',()=>({InboxBadge:({onClick}:any)=><button data-testid="inbox-badge" onClick={onClick}>Inbox</button>}));
const item={id:'pending',title:'Pending policy acknowledgement',itemType:'announcement_ack',priority:'normal',
  isRead:false,status:'pending',createdAt:'2026-10-25T00:00:00Z',sourceType:'announcement',sourceId:'version'};
const page=(title=item.title,isRead=false)=>({records:[{...item,title,isRead}],total:1});
function mount(){render(<MemoryRouter><InboxHeaderWidget /></MemoryRouter>);}

describe('inbox request ordering and read facts',()=>{
  beforeEach(()=>vi.clearAllMocks());
  it('cannot overwrite a reopened current list with a delayed response and restores Escape focus',async()=>{
    let release:(value:unknown)=>void=()=>{};
    state.listInboxItems.mockReturnValueOnce(new Promise(resolve=>{release=resolve;})).mockResolvedValue(page());
    mount();fireEvent.click(screen.getByTestId('inbox-badge'));
    await waitFor(()=>expect(state.listInboxItems).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByTestId('inbox-badge'));fireEvent.click(screen.getByTestId('inbox-badge'));
    await waitFor(()=>expect(screen.getByText(item.title)).toBeVisible());
    await act(async()=>{release(page('Old delayed row'));});
    expect(screen.queryByText('Old delayed row')).not.toBeInTheDocument();
    expect(screen.getByText(item.title)).toBeVisible();
    screen.getByTestId('inbox-mark-all-read').focus();fireEvent.keyDown(document,{key:'Escape'});
    expect(screen.queryByTestId('inbox-dropdown')).not.toBeInTheDocument();expect(screen.getByTestId('inbox-badge')).toHaveFocus();
  });
  it('reloads authoritative rows after read-all instead of fabricating read state on later arrivals',async()=>{
    state.listInboxItems.mockResolvedValueOnce(page()).mockResolvedValue(page('Arrived after the bulk operation',false));
    state.markAllRead.mockResolvedValue(undefined);mount();fireEvent.click(screen.getByTestId('inbox-badge'));
    await waitFor(()=>expect(screen.getByText(item.title)).toBeVisible());
    fireEvent.click(screen.getByTestId('inbox-mark-all-read'));
    await waitFor(()=>expect(screen.getByText('Arrived after the bulk operation')).toBeVisible());
    expect(state.markAllRead).toHaveBeenCalledOnce();expect(state.listInboxItems).toHaveBeenCalledTimes(2);
  });
});
