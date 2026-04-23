import { useState, useEffect, useRef } from 'react';
import {
  Form,
  useActionData,
  useNavigation,
  useLoaderData,
  useSearchParams,
  Link,
  type LoaderFunctionArgs,
  type ActionFunctionArgs,
} from 'react-router';
import { getUserProfile, updateUserProfile, uploadAvatar } from '~/shared/services/profile';
import type { UserProfile, UpdateUserProfileRequest } from '~/types/profile';
import { useToast } from '~/contexts/ToastContext';
import PasswordChangeForm from '~/ui/security/PasswordChangeForm';

// Loader函数 - 获取用户资料
export async function loader({ request }: LoaderFunctionArgs) {
  try {
    const profile = await getUserProfile(request);
    return { profile, error: null };
  } catch (error) {
    console.error('Failed to load user profile:', error);
    return {
      profile: null,
      error: error instanceof Error ? error.message : 'Failed to load profile',
    };
  }
}

// Action函数 - 处理表单提交
export async function action({ request }: ActionFunctionArgs) {
  const formData = await request.formData();
  const intent = formData.get('intent') as string;

  if (intent === 'update-profile') {
    try {
      // 数据验证
      const email = formData.get('email') as string;
      if (!email || !email.includes('@')) {
        return {
          success: false,
          error: '请输入有效的邮箱地址',
          field: 'email',
        };
      }

      const updateData: UpdateUserProfileRequest = {
        nickName: (formData.get('nickName') as string) || undefined,
        email,
        mobile: (formData.get('mobile') as string) || undefined,
        area: (formData.get('area') as string) || undefined,
        signature: (formData.get('signature') as string) || undefined,
      };

      const updatedProfile = await updateUserProfile(request, updateData);
      return {
        success: true,
        profile: updatedProfile,
        message: '个人资料更新成功',
      };
    } catch (error) {
      console.error('Failed to update profile:', error);
      return {
        success: false,
        error: error instanceof Error ? error.message : '更新个人资料失败',
      };
    }
  }

  return { success: false, error: '无效的操作' };
}

// 头像上传组件
function AvatarUpload({
  profile,
  isEditing,
  onUploadSuccess,
}: {
  profile: UserProfile;
  isEditing: boolean;
  onUploadSuccess: (fileId: string) => void;
}) {
  const { showSuccessToast, showErrorToast } = useToast();
  const showToast = (message: string, type: 'success' | 'error') => {
    if (type === 'success') {
      showSuccessToast(message);
    } else {
      showErrorToast(message);
    }
  };
  const [isUploading, setIsUploading] = useState(false);

  const handleAvatarUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    // 文件类型验证
    if (!file.type.startsWith('image/')) {
      showToast('请选择图片文件', 'error');
      return;
    }

    // 文件大小验证 (5MB)
    if (file.size > 5 * 1024 * 1024) {
      showToast('图片大小不能超过5MB', 'error');
      return;
    }

    setIsUploading(true);
    try {
      const uploadResult = await uploadAvatar(file);
      if (uploadResult) {
        onUploadSuccess(uploadResult);
        showToast('头像上传成功', 'success');
      } else {
        throw new Error('上传结果无效');
      }
    } catch (error) {
      console.error('Avatar upload failed:', error);
      showToast('头像上传失败', 'error');
    } finally {
      setIsUploading(false);
    }
  };

  return (
    <div className="text-center">
      <div className="relative inline-block">
        <img
          src={profile.avatarUrl || '/avatar.jpeg'}
          alt="用户头像"
          className="h-32 w-32 rounded-full border-4 border-gray-200 object-cover"
        />
        {isEditing && (
          <label className="absolute right-0 bottom-0 cursor-pointer rounded-full bg-blue-600 p-2 text-white transition-colors hover:bg-blue-700">
            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z"
              />
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M15 13a3 3 0 11-6 0 3 3 0 016 0z"
              />
            </svg>
            <input
              type="file"
              accept="image/*"
              onChange={handleAvatarUpload}
              className="hidden"
              disabled={isUploading}
            />
          </label>
        )}
        {isUploading && (
          <div className="bg-opacity-50 absolute inset-0 flex items-center justify-center rounded-full bg-black">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-white"></div>
          </div>
        )}
      </div>
      <p className="mt-4 text-sm text-gray-600">点击头像更换图片</p>
      <p className="text-xs text-gray-500">支持 JPG、PNG 格式，文件大小不超过 5MB</p>
    </div>
  );
}

// 表单字段组件
function FormField({
  label,
  name,
  type = 'text',
  value,
  onChange,
  required = false,
  disabled = false,
  placeholder,
  rows,
  helpText,
}: {
  label: string;
  name: string;
  type?: string;
  value: string;
  onChange: (value: string) => void;
  required?: boolean;
  disabled?: boolean;
  placeholder?: string;
  rows?: number;
  helpText?: string;
}) {
  const inputClasses = `w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500 ${
    disabled ? 'bg-gray-100 text-gray-500' : ''
  }`;

  return (
    <div>
      <label className="mb-2 block text-sm font-medium text-gray-700">
        {label} {required && <span className="text-red-500">*</span>}
      </label>
      {type === 'textarea' ? (
        <textarea
          name={name}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={rows || 3}
          className={inputClasses}
          placeholder={placeholder}
          disabled={disabled}
        />
      ) : (
        <input
          type={type}
          name={name}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          required={required}
          disabled={disabled}
          className={inputClasses}
          placeholder={placeholder}
        />
      )}
      {helpText && <p className="mt-1 text-xs text-gray-500">{helpText}</p>}
    </div>
  );
}

// 信息显示组件
function InfoDisplay({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>
      <p className="text-gray-900">{value || '未设置'}</p>
    </div>
  );
}

// 加载状态组件
function LoadingSpinner() {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div className="text-center">
        <div className="mx-auto mb-4 h-12 w-12 animate-spin rounded-full border-b-2 border-blue-600"></div>
        <p className="text-gray-600">加载中...</p>
      </div>
    </div>
  );
}

// 错误状态组件
function ErrorDisplay({ error, onRetry }: { error: string; onRetry: () => void }) {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div className="text-center">
        <div className="mb-4 text-red-500">
          <svg className="mx-auto h-16 w-16" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.732 16.5c-.77.833.192 2.5 1.732 2.5z"
            />
          </svg>
        </div>
        <p className="mb-4 text-gray-600">{error}</p>
        <button
          onClick={onRetry}
          className="rounded-md bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
          data-testid="profile-retry-btn"
        >
          重试
        </button>
      </div>
    </div>
  );
}

export default function PersonalProfile() {
  const { showSuccessToast: showSuccessToast2, showErrorToast: showErrorToast2 } = useToast();
  const showToast2 = (message: string, type: 'success' | 'error') => {
    if (type === 'success') {
      showSuccessToast2(message);
    } else {
      showErrorToast2(message);
    }
  };
  const navigation = useNavigation();
  const actionData = useActionData<typeof action>();
  const loaderData = useLoaderData<typeof loader>();

  const [profile, setProfile] = useState<UserProfile | null>(loaderData?.profile || null);
  const [isEditing, setIsEditing] = useState(false);
  const [formData, setFormData] = useState<UpdateUserProfileRequest>({});
  const [_retryCount, setRetryCount] = useState(0);
  const [searchParams] = useSearchParams();
  const forceChangePassword = searchParams.get('forceChangePassword') === 'true';
  const securityRef = useRef<HTMLDivElement>(null);

  // 初始化表单数据
  useEffect(() => {
    if (profile) {
      setFormData({
        nickName: profile.nickName || '',
        email: profile.email,
        mobile: profile.mobile || '',
        area: profile.area || '',
        signature: profile.signature || '',
      });
    }
  }, [profile]);

  // Auto-scroll to security section if forceChangePassword
  useEffect(() => {
    if (forceChangePassword && securityRef.current) {
      securityRef.current.scrollIntoView({ behavior: 'smooth' });
    }
  }, [forceChangePassword, profile]);

  // 处理loader数据更新
  useEffect(() => {
    if (loaderData?.profile) {
      setProfile(loaderData.profile);
    } else if (loaderData?.error) {
      showToast2(loaderData.error, 'error');
    }
  }, [loaderData, showToast2]);

  // 处理action结果
  useEffect(() => {
    if (actionData) {
      if (actionData.success) {
        showToast2(actionData.message || '操作成功', 'success');
        if (actionData.profile) {
          setProfile(actionData.profile);
        }
        setIsEditing(false);
      } else {
        showToast2(actionData.error || '操作失败', 'error');
      }
    }
  }, [actionData, showToast2]);

  // 处理表单输入变化
  const handleInputChange = (field: keyof UpdateUserProfileRequest, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
  };

  // 处理头像上传成功
  const handleAvatarUploadSuccess = (fileId: string) => {
    // 更新本地状态
    setFormData((prev) => ({ ...prev, imgId: fileId }));

    // 显示成功提示
    showToast2('头像上传成功', 'success');
  };

  // 重试加载
  const handleRetry = () => {
    setRetryCount((prev) => prev + 1);
    window.location.reload();
  };

  // 取消编辑
  const handleCancelEdit = () => {
    setIsEditing(false);
    if (profile) {
      setFormData({
        nickName: profile.nickName || '',
        email: profile.email,
        mobile: profile.mobile || '',
        area: profile.area || '',
        signature: profile.signature || '',
      });
    }
  };

  const isSubmitting = navigation.state === 'submitting';

  // 错误状态
  if (loaderData?.error && !profile) {
    return <ErrorDisplay error={loaderData.error} onRetry={handleRetry} />;
  }

  // 加载状态
  if (!profile) {
    return <LoadingSpinner />;
  }

  return (
    <div className="space-y-6 p-6">
      <div className="rounded-lg bg-white shadow-md">
        {/* 页面标题 */}
        <div className="border-b border-gray-200 px-6 py-4">
          <div className="flex items-center justify-between">
            <h1 className="text-2xl font-semibold text-gray-900">个人资料</h1>
            {!isEditing && (
              <button
                onClick={() => setIsEditing(true)}
                className="rounded-md bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
                data-testid="profile-edit-btn"
              >
                编辑资料
              </button>
            )}
          </div>
        </div>

        <div className="p-6">
          <div className="grid grid-cols-1 gap-8 lg:grid-cols-3">
            {/* 头像区域 */}
            <div className="lg:col-span-1">
              <AvatarUpload
                profile={profile}
                isEditing={isEditing}
                onUploadSuccess={handleAvatarUploadSuccess}
              />
            </div>

            {/* 用户信息区域 */}
            <div className="lg:col-span-2">
              {isEditing ? (
                <Form method="post" className="space-y-6">
                  <input type="hidden" name="intent" value="update-profile" />

                  <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                    <FormField
                      label="用户名"
                      name="userName"
                      value={profile.userName}
                      onChange={() => {}}
                      disabled
                      helpText="用户名不可修改"
                    />

                    <FormField
                      label="昵称"
                      name="nickName"
                      value={formData.nickName || ''}
                      onChange={(value) => handleInputChange('nickName', value)}
                      placeholder="请输入昵称"
                    />

                    <FormField
                      label="邮箱"
                      name="email"
                      type="email"
                      value={formData.email || ''}
                      onChange={(value) => handleInputChange('email', value)}
                      required
                      placeholder="请输入邮箱"
                    />

                    <FormField
                      label="手机号"
                      name="mobile"
                      type="tel"
                      value={formData.mobile || ''}
                      onChange={(value) => handleInputChange('mobile', value)}
                      placeholder="请输入手机号"
                    />

                    <FormField
                      label="地区"
                      name="area"
                      value={formData.area || ''}
                      onChange={(value) => handleInputChange('area', value)}
                      placeholder="请输入所在地区"
                    />
                  </div>

                  <FormField
                    label="个人签名"
                    name="signature"
                    type="textarea"
                    value={formData.signature || ''}
                    onChange={(value) => handleInputChange('signature', value)}
                    placeholder="请输入个人签名"
                    rows={3}
                  />

                  <div className="flex space-x-4">
                    <button
                      type="submit"
                      disabled={isSubmitting}
                      className="rounded-md bg-blue-600 px-6 py-2 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
                      data-testid="profile-save-btn"
                    >
                      {isSubmitting ? '保存中...' : '保存'}
                    </button>
                    <button
                      type="button"
                      onClick={handleCancelEdit}
                      disabled={isSubmitting}
                      className="rounded-md bg-gray-300 px-6 py-2 text-gray-700 transition-colors hover:bg-gray-400 disabled:opacity-50"
                      data-testid="profile-cancel-btn"
                    >
                      取消
                    </button>
                  </div>
                </Form>
              ) : (
                <div className="space-y-6">
                  <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
                    <InfoDisplay label="用户名" value={profile.userName} />
                    <InfoDisplay label="昵称" value={profile.nickName || ''} />
                    <InfoDisplay label="邮箱" value={profile.email} />
                    <InfoDisplay label="手机号" value={profile.mobile || ''} />
                    <InfoDisplay label="地区" value={profile.area || ''} />
                    <InfoDisplay
                      label="注册时间"
                      value={new Date(profile.createdAt).toLocaleString()}
                    />
                  </div>

                  {profile.signature && (
                    <div>
                      <label className="mb-1 block text-sm font-medium text-gray-700">
                        个人签名
                      </label>
                      <p className="rounded-md bg-gray-50 p-3 text-gray-900">{profile.signature}</p>
                    </div>
                  )}

                  {profile.lastSignInAt && (
                    <InfoDisplay
                      label="最后登录时间"
                      value={new Date(profile.lastSignInAt).toLocaleString()}
                    />
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Social Account Binding Section */}
      <div className="rounded-lg bg-white shadow-md">
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <h2 className="text-xl font-semibold text-gray-900">Social Account Binding</h2>
          <Link
            to="/personal/social-links"
            className="text-sm text-blue-600 hover:text-blue-700 hover:underline"
            data-testid="profile-social-links-link"
          >
            Manage &rarr;
          </Link>
        </div>
        <div className="p-6">
          <p className="text-sm text-gray-500">
            Link your social accounts (WeChat, Google, Apple) for one-click login.
          </p>
        </div>
      </div>

      {/* Security Settings Section */}
      <div ref={securityRef} className="rounded-lg bg-white shadow-md">
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-xl font-semibold text-gray-900">Security Settings</h2>
          {forceChangePassword && (
            <p className="mt-1 text-sm text-amber-600">
              Your password must be changed before continuing.
            </p>
          )}
        </div>
        <div className="p-6">
          <h3 className="mb-4 text-lg font-medium text-gray-800">Change Password</h3>
          <PasswordChangeForm />
        </div>
      </div>

      {/* Account Deactivation Section */}
      <div className="rounded-lg bg-white shadow-md">
        <div className="border-b border-gray-200 px-6 py-4">
          <h2 className="text-xl font-semibold text-red-600">Account Deactivation</h2>
        </div>
        <div className="flex items-center justify-between p-6">
          <p className="text-sm text-gray-500">
            Permanently deactivate your account and anonymize all personal data.
          </p>
          <Link
            to="/personal/deactivation"
            className="rounded-md border border-red-300 px-4 py-2 text-sm whitespace-nowrap text-red-600 transition-colors hover:bg-red-50"
            data-testid="profile-deactivation-link"
          >
            Deactivate Account
          </Link>
        </div>
      </div>
    </div>
  );
}
