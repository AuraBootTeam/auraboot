import { PermissionGuard } from '~/ui/PermissionGuard';
import { DynamicPageUnavailable } from '~/framework/meta/rendering/pages/DynamicPageUnavailable';
import { useNavigate } from 'react-router';
import { VirtualModelWizard } from '~/plugins/core-meta/components/virtual-model/VirtualModelWizard';

export default function NewVirtualModelPage() {
  const navigate = useNavigate();
  return (
    <PermissionGuard permission="meta.model.update" fallback={<DynamicPageUnavailable message="Access denied" />}>
      <VirtualModelWizard
        onComplete={(pid) => navigate(`/meta/models/${pid}`)}
        onCancel={() => navigate('/meta/models/new')}
      />
    </PermissionGuard>
  );
}
