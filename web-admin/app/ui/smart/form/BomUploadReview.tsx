/**
 * Smart wrapper around the meta BomUploadReviewField so DSL form fields can use
 * `component: "BomUploadReview"`. ControlledFieldRenderer supplies value/onChange;
 * accept/previewRowCount/roleOptions come through field.props from the page DSL.
 */
import BomUploadReviewField, {
  type BomUploadReviewValue,
} from '~/framework/meta/runtime/actions/BomUploadReviewField';

export type { BomUploadReviewValue };

export interface BomUploadReviewProps {
  value?: BomUploadReviewValue | null;
  onChange: (value: BomUploadReviewValue | null) => void;
  accept?: string;
  maxBytes?: number;
  previewRowCount?: number;
  roleOptions?: Array<{ value: string; label: Record<string, string> | string }>;
}

export default function BomUploadReview(props: BomUploadReviewProps) {
  const { value, onChange, accept, maxBytes, previewRowCount, roleOptions } = props;
  return (
    <BomUploadReviewField
      fieldName="corrected_bom_file"
      accept={accept}
      maxBytes={maxBytes}
      previewRowCount={previewRowCount}
      roleOptions={roleOptions}
      onChange={onChange}
    />
  );
}
