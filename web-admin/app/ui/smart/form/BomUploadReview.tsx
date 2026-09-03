/**
 * Smart wrapper around the meta BomUploadReviewField so DSL form fields can use
 * `component: "BomUploadReview"`. ControlledFieldRenderer supplies value/onChange;
 * accept/previewRowCount/roleOptions come through field.props from the page DSL.
 * ComponentLoader resolves the manifest's exportName, so the named export is required.
 */
import BomUploadReviewField, {
  type BomUploadReviewValue,
} from '~/framework/meta/runtime/actions/BomUploadReviewField';

export type { BomUploadReviewValue };

export interface BomUploadReviewProps {
  name?: string;
  value?: BomUploadReviewValue | null;
  onChange: (value: BomUploadReviewValue | null) => void;
  accept?: string;
  maxBytes?: number;
  previewRowCount?: number;
  roleOptions?: Array<{ value: string; label: Record<string, string> | string }>;
}

export const BomUploadReview = ({
  name = 'corrected_bom_file',
  value,
  onChange,
  accept,
  maxBytes,
  previewRowCount,
  roleOptions,
}: BomUploadReviewProps) => (
  <BomUploadReviewField
    fieldName={name}
    accept={accept}
    maxBytes={maxBytes}
    previewRowCount={previewRowCount}
    roleOptions={roleOptions}
    onChange={onChange}
  />
);

export default BomUploadReview;
