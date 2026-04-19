// Form Components
export { Input } from '~/ui/smart/form/Input';
export { default as Textarea } from '~/ui/smart/form/Textarea';
export { default as Select } from '~/ui/smart/form/Select';
export { default as Checkbox } from '~/ui/smart/form/Checkbox';
export { default as Radio } from '~/ui/smart/form/Radio';
export { default as DatePicker } from '~/ui/smart/datetime/DatePicker';
export { default as MultiSelect } from '~/ui/smart/form/MultiSelect';
export { default as TreeSelect } from '~/ui/smart/picker/TreeSelect';
export { default as UserSelect } from '~/ui/smart/picker/UserSelect';
export { default as OrganizationSelect } from '~/ui/smart/picker/OrganizationSelect';
export { default as TimeRangePicker } from '~/ui/smart/datetime/TimeRangePicker';
export { default as CascadeSelect } from '~/ui/smart/picker/CascadeSelect';
export { FormRef, FormRefPreview } from '~/ui/smart/form/FormRef';

// Display Components
export { default as Display } from '~/ui/smart/display/Display';
export { default as ImageDisplay } from '~/ui/smart/display/ImageDisplay';
export { default as Table } from '~/ui/smart/display/Table';
export { default as List } from '~/ui/smart/display/List';
export { default as I18nText } from '~/ui/smart/display/I18nText';

// i18n Form Components
export { default as I18nTextInput } from '~/ui/smart/form/I18nTextInput';

// Interaction Components
export { default as Button } from '~/ui/smart/interaction/Button';
export { default as Navigation } from '~/ui/smart/interaction/Navigation';

// Layout Components
export { default as Form } from '~/ui/smart/layout/Form';
export { default as Layout } from '~/ui/smart/layout/Layout';

// DateTime Components
export { default as Date } from '~/ui/smart/datetime/Date';
export { default as Datetime } from '~/ui/smart/datetime/Datetime';

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
export * from '~/ui/smart/types';
export * from '~/ui/smart/ui';

// Import components for the component map
import { Input as InputComponent } from '~/ui/smart/form/Input';
import TextareaComponent from '~/ui/smart/form/Textarea';
import SelectComponent from '~/ui/smart/form/Select';
import CheckboxComponent from '~/ui/smart/form/Checkbox';
import RadioComponent from '~/ui/smart/form/Radio';
import DatePickerComponent from '~/ui/smart/datetime/DatePicker';
import MultiSelectComponent from '~/ui/smart/form/MultiSelect';
import TreeSelectComponent from '~/ui/smart/picker/TreeSelect';
import UserSelectComponent from '~/ui/smart/picker/UserSelect';
import OrganizationSelectComponent from '~/ui/smart/picker/OrganizationSelect';
import TimeRangePickerComponent from '~/ui/smart/datetime/TimeRangePicker';
import CascadeSelectComponent from '~/ui/smart/picker/CascadeSelect';
import { FormRef as FormRefComponent } from '~/ui/smart/form/FormRef';
import DisplayComponent from '~/ui/smart/display/Display';
import ImageDisplayComponent from '~/ui/smart/display/ImageDisplay';
import TableComponent from '~/ui/smart/display/Table';
import ListComponent from '~/ui/smart/display/List';
import I18nTextComponent from '~/ui/smart/display/I18nText';
import I18nTextInputComponent from '~/ui/smart/form/I18nTextInput';
import ButtonComponent from '~/ui/smart/interaction/Button';
import NavigationComponent from '~/ui/smart/interaction/Navigation';
import FormComponent from '~/ui/smart/layout/Form';
import LayoutComponent from '~/ui/smart/layout/Layout';
import DateComponent from '~/ui/smart/datetime/Date';
import DatetimeComponent from '~/ui/smart/datetime/Datetime';

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
