import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
const { requestMock, formMock } = vi.hoisted(() => ({ requestMock: vi.fn(), formMock: vi.fn() }));
vi.mock('~/shared/services/http-client/internal-api', () => ({
  internalRequest: requestMock,
  internalFormRequest: formMock,
}));
import {
  getUserProfile,
  updateUserProfile,
  uploadAvatar,
  profileUpdateFromFormData,
} from '../profile';

const request = new Request('http://fixture.invalid/personal/profile');
const profile = {
  pid: 'user-owned',
  userName: 'owned',
  email: null,
  nickName: null,
  createdAt: null,
};
const ok = (data: unknown) => ({ code: '0', data });
const failure = { code: '1', desc: 'Denied' };

describe('generated profile service', () => {
  beforeEach(() => {
    requestMock.mockReset();
    formMock.mockReset();
  });
  afterEach(() => vi.unstubAllGlobals());

  it('reads the current profile without a caller-supplied user identity and retains null metadata', async () => {
    requestMock.mockResolvedValue(ok(profile));
    expect(await getUserProfile(request)).toEqual(profile);
    expect(requestMock).toHaveBeenCalledWith('get', '/api/user/profile', { request });
  });
  it.each([undefined, null, {}, { pid: 'pid' }, { userName: 'name' }])(
    'refuses missing profile identity: %j',
    async (data) => {
      requestMock.mockResolvedValue(ok(data));
      await expect(getUserProfile(request)).rejects.toThrow('identity');
    },
  );
  it('throws the actual read failure', async () => {
    requestMock.mockResolvedValue(failure);
    await expect(getUserProfile(request)).rejects.toThrow('Denied');
  });
  it('saves the avatar identity as JSON and requires the returned profile identity', async () => {
    const body = { nickName: 'Owned', imgId: 'file-owned' };
    requestMock.mockResolvedValue(ok({ ...profile, ...body }));
    expect(await updateUserProfile(request, body)).toMatchObject(body);
    expect(requestMock).toHaveBeenCalledWith('put', '/api/user/profile', { body, request });
    requestMock.mockResolvedValue(ok(null));
    await expect(updateUserProfile(request, body)).rejects.toThrow('identity');
  });
  it('does not treat a rejected update as success', async () => {
    requestMock.mockResolvedValue(failure);
    await expect(updateUserProfile(request, {})).rejects.toThrow('Denied');
  });
  it('uploads a File through the shared multipart operation and returns the file object', async () => {
    const file = new File(['image'], 'owned.png', { type: 'image/png' });
    const uploaded = {
      fileId: 'file-owned',
      url: '/api/file/download/file-owned',
      fileName: null,
      createdBy: null,
    };
    formMock.mockResolvedValue(ok(uploaded));
    expect(await uploadAvatar(file, request)).toEqual(uploaded);
    expect(formMock).toHaveBeenCalledWith('post', '/api/user/avatar/upload', {
      body: { file },
      request,
    });
  });
  it.each(['old-handwritten-file-string', null, {}, { fileId: 'file-owned' }, { url: '/file' }])(
    'rejects unusable upload responses: %j',
    async (data) => {
      formMock.mockResolvedValue(ok(data));
      await expect(uploadAvatar(new File([], 'image.png'))).rejects.toThrow('identity or URL');
    },
  );
  it('preserves upload rejection', async () => {
    formMock.mockResolvedValue(failure);
    await expect(uploadAvatar(new File([], 'image.png'))).rejects.toThrow('Denied');
  });
  it('accepts a no-email profile edit, carries imgId and excludes credential fields', () => {
    const form = new FormData();
    form.set('nickName', 'Owned');
    form.set('imgId', 'file-owned');
    form.set('email', 'unverified@example.invalid');
    form.set('userId', 'other-user');
    expect(profileUpdateFromFormData(form)).toEqual({
      nickName: 'Owned',
      imgId: 'file-owned',
      mobile: undefined,
      area: undefined,
      signature: undefined,
    });
  });
  it('rejects a binary value masquerading as a JSON profile field', () => {
    const form = new FormData();
    form.set('imgId', new Blob(['bad']));
    expect(() => profileUpdateFromFormData(form)).toThrow('Invalid profile field: imgId');
  });
});
