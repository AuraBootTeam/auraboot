/**
 * Page Designer - Page List Route
 *
 * Management center for all pages in the page designer.
 *
 * @since 3.2.0
 */

import React, { useState, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { PageList, NewPageWizard } from '~/studio/workbench/panels';
import type { PageMeta } from '~/studio/services/page-manager';

/**
 * Page Designer Management Center
 */
export default function PageDesignerList() {
  const navigate = useNavigate();
  const [showWizard, setShowWizard] = useState(false);

  const handleCreateNew = useCallback(() => {
    setShowWizard(true);
  }, []);

  const handleOpenPage = useCallback(
    (page: PageMeta) => {
      navigate(`/page-designer/${page.id}`);
    },
    [navigate],
  );

  const handleImport = useCallback(() => {
    // TODO: Implement import functionality
  }, []);

  const handleWizardClose = useCallback(() => {
    setShowWizard(false);
  }, []);

  const handleWizardSuccess = useCallback(
    (pageId: string) => {
      setShowWizard(false);
      navigate(`/page-designer/${pageId}`);
    },
    [navigate],
  );

  return (
    <>
      <PageList onCreateNew={handleCreateNew} onOpenPage={handleOpenPage} onImport={handleImport} />
      <NewPageWizard
        isOpen={showWizard}
        onClose={handleWizardClose}
        onSuccess={handleWizardSuccess}
      />
    </>
  );
}
