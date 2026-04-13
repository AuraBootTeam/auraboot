// Form Components
export { Input } from '~/components/smart/form/Input';
export { default as Textarea } from '~/components/smart/form/Textarea';
export { default as Select } from '~/components/smart/form/Select';
export { default as Checkbox } from '~/components/smart/form/Checkbox';
export { default as Radio } from '~/components/smart/form/Radio';
export { default as DatePicker } from '~/components/smart/datetime/DatePicker';
export { default as MultiSelect } from '~/components/smart/form/MultiSelect';
export { default as TreeSelect } from '~/components/smart/picker/TreeSelect';
export { default as UserSelect } from '~/components/smart/picker/UserSelect';
export { default as OrganizationSelect } from '~/components/smart/picker/OrganizationSelect';
export { default as TimeRangePicker } from '~/components/smart/datetime/TimeRangePicker';
export { default as CoordinatesPicker } from '~/components/smart/picker/CoordinatesPicker';
export { default as CascadeSelect } from '~/components/smart/picker/CascadeSelect';
export { FormRef, FormRefPreview } from '~/components/smart/form/FormRef';

// Display Components
export { default as Display } from '~/components/smart/display/Display';
export { default as ImageDisplay } from '~/components/smart/display/ImageDisplay';
export { default as Table } from '~/components/smart/display/Table';
export { default as List } from '~/components/smart/display/List';
export { default as I18nText } from '~/components/smart/display/I18nText';

// i18n Form Components
export { default as I18nTextInput } from '~/components/smart/form/I18nTextInput';

// Interaction Components
export { default as Button } from '~/components/smart/interaction/Button';
export { default as Navigation } from '~/components/smart/interaction/Navigation';

// Layout Components
export { default as Form } from '~/components/smart/layout/Form';
export { default as Layout } from '~/components/smart/layout/Layout';

// DateTime Components
export { default as Date } from '~/components/smart/datetime/Date';
export { default as Datetime } from '~/components/smart/datetime/Datetime';

// Registry (sourced from meta metadata)
export {
  ComponentRegistry,
  componentRegistry,
  initializeComponentRegistry,
  COMPONENT_CATEGORIES,
} from '~/framework/meta/registry/components';
export type { ComponentConfig, ComponentCategory } from '~/framework/meta/registry/components';
export {
  FORM_COMPONENT_CONFIGS,
  DISPLAY_COMPONENT_CONFIGS,
  INTERACTION_COMPONENT_CONFIGS,
  LAYOUT_COMPONENT_CONFIGS,
  DATETIME_COMPONENT_CONFIGS,
  ALL_COMPONENT_CONFIGS,
} from '~/framework/meta/registry/components';

// Types
export * from '~/components/smart/types';
export * from '~/components/smart/ui';

// Import components for the component map
import { Input as InputComponent } from '~/components/smart/form/Input';
import TextareaComponent from '~/components/smart/form/Textarea';
import SelectComponent from '~/components/smart/form/Select';
import CheckboxComponent from '~/components/smart/form/Checkbox';
import RadioComponent from '~/components/smart/form/Radio';
import DatePickerComponent from '~/components/smart/datetime/DatePicker';
import MultiSelectComponent from '~/components/smart/form/MultiSelect';
import TreeSelectComponent from '~/components/smart/picker/TreeSelect';
import UserSelectComponent from '~/components/smart/picker/UserSelect';
import OrganizationSelectComponent from '~/components/smart/picker/OrganizationSelect';
import TimeRangePickerComponent from '~/components/smart/datetime/TimeRangePicker';
import CoordinatesPickerComponent from '~/components/smart/picker/CoordinatesPicker';
import CascadeSelectComponent from '~/components/smart/picker/CascadeSelect';
import { FormRef as FormRefComponent } from '~/components/smart/form/FormRef';
import DisplayComponent from '~/components/smart/display/Display';
import ImageDisplayComponent from '~/components/smart/display/ImageDisplay';
import TableComponent from '~/components/smart/display/Table';
import ListComponent from '~/components/smart/display/List';
import I18nTextComponent from '~/components/smart/display/I18nText';
import I18nTextInputComponent from '~/components/smart/form/I18nTextInput';
import ButtonComponent from '~/components/smart/interaction/Button';
import NavigationComponent from '~/components/smart/interaction/Navigation';
import FormComponent from '~/components/smart/layout/Form';
import LayoutComponent from '~/components/smart/layout/Layout';
import DateComponent from '~/components/smart/datetime/Date';
import DatetimeComponent from '~/components/smart/datetime/Datetime';

// Component Map for dynamic rendering
export const SMART_COMPONENT_MAP = {
  // Form
  input: InputComponent,
  textarea: TextareaComponent,
  select: SelectComponent,
  checkbox: CheckboxComponent,
  radio: RadioComponent,
  datepicker: DatePickerComponent,
  multiselect: MultiSelectComponent,
  treeselect: TreeSelectComponent,
  userselect: UserSelectComponent,
  organizationselect: OrganizationSelectComponent,
  timerangepicker: TimeRangePickerComponent,
  coordinatespicker: CoordinatesPickerComponent,
  cascadeselect: CascadeSelectComponent,
  formref: FormRefComponent,

  // Display
  display: DisplayComponent,
  imagedisplay: ImageDisplayComponent,
  table: TableComponent,
  list: ListComponent,
  i18ntext: I18nTextComponent,

  // i18n Form
  i18ntextinput: I18nTextInputComponent,

  // Interaction
  button: ButtonComponent,
  navigation: NavigationComponent,

  // Layout
  form: FormComponent,
  layout: LayoutComponent,

  // DateTime
  date: DateComponent,
  datetime: DatetimeComponent,
};

// Utility functions
export const getSmartComponent = (type: string) => {
  return SMART_COMPONENT_MAP[type as keyof typeof SMART_COMPONENT_MAP];
};

export const getAvailableComponentTypes = () => {
  return Object.keys(SMART_COMPONENT_MAP);
};
