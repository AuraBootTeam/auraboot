import { internalRequest, type InternalResponseData, type InternalRequestInput } from '~/shared/services/http-client/internal-api';
import { ResultHelper, type Result } from '~/utils/type';
export type NavigationSnapshot = InternalResponseData<'get','/api/navigation/configuration'>;
export type NavigationDefinition = InternalRequestInput<'post','/api/navigation/entries'>['body']['definition'];
export class NavigationConflict extends Error {}
function unwrap<T>(result: Result<T>): T {
  if(String(result.code)==='41001' || String(result.code)==='409') throw new NavigationConflict('Navigation changed');
  if(!ResultHelper.isSuccess(result)||result.data==null)throw new Error(result.message||'Navigation request failed');
  return result.data;
}
export const navigationService={
  async read(){return unwrap(await internalRequest('get','/api/navigation/configuration',{}));},
  async create(expectedRevision:string,definition:NavigationDefinition){return unwrap(await internalRequest('post','/api/navigation/entries',{body:{expectedRevision,definition}}));},
  async update(pid:string,expectedRevision:string,definition:NavigationDefinition){return unwrap(await internalRequest('put','/api/navigation/entries/{pid}',{path:{pid},body:{expectedRevision,definition}}));},
  async remove(pid:string,expectedRevision:string){return unwrap(await internalRequest('delete','/api/navigation/entries/{pid}',{path:{pid},body:{expectedRevision}}));},
  async restore(pid:string,expectedRevision:string){return unwrap(await internalRequest('post','/api/navigation/entries/{pid}/restore',{path:{pid},body:{expectedRevision}}));},
  async target(pid:string){return unwrap(await internalRequest('get','/api/navigation/entries/{pid}',{path:{pid}}));},
};
