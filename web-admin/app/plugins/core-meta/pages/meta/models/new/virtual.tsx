import { useNavigate } from 'react-router';
import { VirtualModelWizard } from '~/plugins/core-meta/components/virtual-model/VirtualModelWizard';

export default function NewVirtualModelPage() {
  const navigate = useNavigate();
  return (
    <VirtualModelWizard
      onComplete={(pid) => navigate(`/meta/models/${pid}`)}
      onCancel={() => navigate('/meta/models/new')}
    />
  );
}
