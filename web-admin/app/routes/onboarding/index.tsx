import React, { useCallback } from 'react';
import { useNavigate } from 'react-router';
import { OnboardingWizard } from '~/framework/smart/onboarding';

/**
 * Standalone onboarding page.
 * Users can also be directed here from the main layout when first-login is detected.
 */
export default function OnboardingPage() {
  const navigate = useNavigate();

  const handleComplete = useCallback(() => {
    navigate('/');
  }, [navigate]);

  return <OnboardingWizard onComplete={handleComplete} />;
}
