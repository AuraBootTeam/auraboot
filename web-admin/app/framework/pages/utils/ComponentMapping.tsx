import type React from 'react';
// import { SmartInput, SmartInputSideBar } from '../old_just_backup_basic_components/SmartInput';
// import { SmartSelect, SmartSelectSideBar } from '../old_just_backup_basic_components/SmartSelect';
// import { SmartDate } from '../old_just_backup_basic_components/SmartDate';
// import { SmartDatePicker } from '../old_just_backup_basic_components/SmartDatePicker';
// import SmartImage from '../old_just_backup_basic_components/SmartImage';
// import { SmartRadio } from '../old_just_backup_basic_components/SmartRadio';
// import { SmartCheckbox } from '../old_just_backup_basic_components/SmartCheckbox';
// import { SmartFileUpload } from '../old_just_backup_basic_components/SmartFileUpload';
// import { SmartTextarea } from '../old_just_backup_basic_components/SmartTextarea';
// import { SmartMultiSelect } from '../old_just_backup_basic_components/SmartMultiSelect';
// import { SmartTreeSelect } from '../old_just_backup_basic_components/SmartTreeSelect';
// import { CascadeSelect } from '../old_just_backup_basic_components/CascadeSelect';
// import { SmartDiv } from '../old_just_backup_basic_components/SmartDiv';

// const options = {
//     title: "Demo Title",
//     autoHide: true,
//     todayBtn: false,
//     clearBtn: true,
//     clearBtnText: "Clear",
//     maxDate: new Date("2030-01-01"),
//     minDate: new Date("1950-01-01"),
//     theme: {
//         background: "bg-gray-700 dark:bg-gray-800",
//         todayBtn: "",
//         clearBtn: "",
//         icons: "",
//         text: "",
//         disabledText: "bg-red-500",
//         input: "",
//         inputIcon: "",
//         selected: "",
//     },
//     icons: {
//         // () => ReactElement | JSX.Element
//         prev: () => <span>Previous</span>,
//         next: () => <span>Next</span>,
//     },
//     datepickerClassNames: "top-12",
//     defaultDate: new Date("2022-01-01"),
//     language: "en",
//     disabledDates: [],
//     weekDays: ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"],
//     inputNameProp: "date",
//     inputIdProp: "date",
//     inputPlaceholderProp: "Select Date",
//     inputDateFormatProp: {
//         day: "numeric",
//         month: "long",
//         year: "numeric"
//     }
// }

export const KeysToComponentMap: Record<string, React.ComponentType<any>> = {};

// // These will be available from the sidebar
// export const FormDesignerComponents = [
//     {
//         type: "input",
//         title: "单行输入"
//     },
//     {
//         type: "textarea",
//         title: "多行输入"
//     },
//     {
//         type: "select",
//         title: "下拉框"
//     },
//     {
//         type: "radio",
//         title: "单选项"
//     },
//     {
//         type: "checkbox",
//         title: "多选项"
//     },
//     {
//         type: "date",
//         title: "日期"
//     },
//     {
//         type: "file_upload",
//         title: "文件上传"
//     },
//     {
//         type: "image_upload",
//         title: "图片上传"
//     },
//     {
//         type: "div",
//         title: "容器"
//     },

// ];

// // These define how we render the field
// export const FormDesignerComponentRenders = {
//     input: () =>
//         <div className=" flex justify-between w-11/12 ">
//             <label htmlFor="first_name" className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white">Text</label>
//             <input type="text" id="first_name" className="bg-gray-50 border border-gray-300 text-gray-900 text-sm rounded-lg focus:ring-blue-500 focus:border-blue-500 block w-full p-2.5 dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-blue-500 dark:focus:border-blue-500" placeholder="John" required />

//         </div>

//     ,
//     textarea: () =>
//         <div className="flex justify-between  w-11/12">
//             <label htmlFor="message" className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white">TextArea</label>
//             <textarea id="message" rows="4" className="block p-2.5 w-full text-sm text-gray-900 bg-gray-50 rounded-lg border border-gray-300 focus:ring-blue-500 focus:border-blue-500 dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-blue-500 dark:focus:border-blue-500" placeholder="Write your thoughts here..."></textarea>
//         </div>
//     ,
//     select: () => (
//         <div className="flex justify-between">
//             <label htmlFor="countries" className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white">Select</label>
//             <select id="countries" className="bg-gray-50 border border-gray-300 text-gray-900 text-sm rounded-lg focus:ring-blue-500 focus:border-blue-500 block w-full p-2.5 dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-blue-500 dark:focus:border-blue-500">
//                 <option >Choose a country</option>
//                 <option value="US">United States</option>
//                 <option value="CA">Canada</option>
//                 <option value="FR">France</option>
//                 <option value="DE">Germany</option>
//             </select>
//         </div>
//     ),
//     radio: () => (
//         <div className="flex justify-start">
//             <label className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white" >Radio</label>

//             <fieldset>
//                 <input className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 " type="radio" id="radio1" name="radio" value="radio1" defaultChecked />
//                 <label className="mr-2 mt-2 font-dark:text-white" htmlFor="huey">Radio1</label>
//                 <input className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 " type="radio" id="radio2" name="radio" value="radio2" />
//                 <label className="mr-2 mt-2 font-medium dark:text-white" htmlFor="dewey">Radio2</label>
//                 <input className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 " type="radio" id="radio3" name="radio" value="radio3" />
//                 <label className="mr-2 mt-2 font-medium dark:text-white" htmlFor="louie">Radio3</label>
//             </fieldset>
//         </div>

//     ),
//     checkbox: () => (
//         <div className="flex justify-start">
//             <label className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white" >CheckBox</label>

//             <fieldset className="justify-start">
//                 <input className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 " type="checkbox" id="checkbox1" name="radio" value="checkbox1" defaultChecked />
//                 <label className="mr-2 mt-2 font-dark:text-white" htmlFor="checkbox1">Checkbox1</label>
//                 <input className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 " type="checkbox" id="checkbox2" name="radio" value="checkbox2" />
//                 <label className="mr-2 mt-2 font-dark:text-white" htmlFor="checkbox2">Checkbox2</label>
//                 <input className="w-4 h-4 text-blue-600 bg-gray-100 border-gray-300 " type="checkbox" id="checkbox3" name="radio" value="checkbox3" />
//                 <label className="mr-2 mt-2 font-dark:text-white" htmlFor="checkbox3">Checkbox3</label>
//             </fieldset>
//         </div>
//     ),
//     date: () => (
//         <div className="flex items-center">
//             <label htmlFor="file_upload" className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white">Date</label>

//             <SmartDate />
//         </div>
//     ),
//     file_upload: () => (
//         <div className="flex items-center">

//             <label htmlFor="file_upload" className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white">File Upload</label>
//             <input className="block w-full text-sm text-gray-900 border border-gray-300 rounded-lg cursor-pointer bg-gray-50 dark:text-gray-400 focus:outline-none dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400" id="file_input" type="file" />

//         </div>
//     ),
//     image_upload: () => (
//         <div className="flex items-center">
//             <label htmlFor="file_upload" className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white">File Upload</label>

//             <label htmlFor="dropzone-file" className="flex flex-col items-center justify-center w-full h-64 border-2 border-gray-300 border-dashed rounded-lg cursor-pointer bg-gray-50 dark:hover:bg-bray-800 dark:bg-gray-700 hover:bg-gray-100 dark:border-gray-600 dark:hover:border-gray-500 dark:hover:bg-gray-600">
//                 <div className="flex flex-col items-center justify-center pt-5 pb-6">
//                     <svg className="w-8 h-8 mb-4 text-gray-500 dark:text-gray-400" aria-hidden="true" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 20 16">
//                         <path stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M13 13h3a3 3 0 0 0 0-6h-.025A5.56 5.56 0 0 0 16 6.5 5.5 5.5 0 0 0 5.207 5.021C5.137 5.017 5.071 5 5 5a4 4 0 0 0 0 8h2.167M10 15V6m0 0L8 8m2-2 2 2" />
//                     </svg>
//                     <p className="mb-2 text-sm text-gray-500 dark:text-gray-400"><span className="font-semibold">Click to upload</span> or drag and drop</p>
//                     <p className="text-xs text-gray-500 dark:text-gray-400">SVG, PNG, JPG or GIF (MAX. 800x400px)</p>
//                 </div>
//                 <input id="dropzone-file" type="file" className="hidden" />
//             </label>
//         </div>
//     ),
//     div: () => (
//         <div className="flex items-center">
//             <label className="w-1/12 mr-2 mt-2 font-medium text-gray-900 dark:text-white">容器</label>
//             <div className="w-full p-4 border-2 border-dashed border-gray-300 rounded-lg bg-gray-50 dark:bg-gray-700 dark:border-gray-600">
//                 <div className="text-center text-gray-500 dark:text-gray-400">
//                     📦 容器组件
//                 </div>
//             </div>
//         </div>
//     ),
// };

// // TODO  IT IS NOT CLEAN!
// export const KeysToComponentMap = {
//     input: SmartInput,
//     select: SmartSelect,
//     image: SmartImage,
//     date: SmartDate,
//     datepicker: SmartDatePicker,
//     radio: SmartRadio,
//     checkbox: SmartCheckbox,
//     fileupload: SmartFileUpload,
//     textarea: SmartTextarea,
//     multiselect:SmartMultiSelect,
//     treeselect:SmartTreeSelect,
//     cascadeSelect:CascadeSelect,
//     div: SmartDiv,
// };

// export const KeysSideBarToComponentMap = {
//     input: SmartInputSideBar,
//     select: SmartSelectSideBar,
//     form: SmartSelectSideBar,
// };

// export const getRightSideBarRender = (type) => {
//     return RightSideBarRender[type] || RightSideBarRender.form;
// }

// export const RightSideBarRender = {
//     form: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>表单设置</div>

//             <div className="space-y-3">
//                 <div>
//                     <label htmlFor="title" className="block text-sm font-medium text-gray-700 mb-1">表单标题</label>
//                     <input
//                         id="title"
//                         name="title"
//                         onChange={onChange}
//                         value={focusItem?.title || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="请输入表单标题"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">布局方式</label>
//                     <select
//                         name="layout"
//                         onChange={onChange}
//                         value={focusItem?.layout || 'auto'}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                     >
//                         <option value="auto">自动布局</option>
//                         <option value="grid">网格布局</option>
//                         <option value="flex">弹性布局</option>
//                     </select>
//                 </div>
//             </div>
//         </div>
//     ),

//     input: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>输入框属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">占位符</label>
//                     <input
//                         name="placeholder"
//                         onChange={onChange}
//                         value={focusItem?.placeholder || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="占位符文本"
//                     />
//                 </div>
//                 <div className="flex items-center space-x-2">
//                     <input
//                         type="checkbox"
//                         name="required"
//                         onChange={onChange}
//                         checked={focusItem?.required || false}
//                         className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
//                     />
//                     <label className="text-sm font-medium text-gray-700">必填字段</label>
//                 </div>
//             </div>
//         </div>
//     ),

//     textarea: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>文本域属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">占位符</label>
//                     <input
//                         name="placeholder"
//                         onChange={onChange}
//                         value={focusItem?.placeholder || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="占位符文本"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">行数</label>
//                     <input
//                         type="number"
//                         name="rows"
//                         onChange={onChange}
//                         value={focusItem?.rows || 4}
//                         min="2"
//                         max="10"
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                     />
//                 </div>
//                 <div className="flex items-center space-x-2">
//                     <input
//                         type="checkbox"
//                         name="required"
//                         onChange={onChange}
//                         checked={focusItem?.required || false}
//                         className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
//                     />
//                     <label className="text-sm font-medium text-gray-700">必填字段</label>
//                 </div>
//             </div>
//         </div>
//     ),

//     select: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>下拉框属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">选项配置</label>
//                     <textarea
//                         name="options"
//                         onChange={onChange}
//                         value={focusItem?.options || '选项1\n选项2\n选项3'}
//                         rows="4"
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="每行一个选项"
//                     />
//                     <p className="text-xs text-gray-500 mt-1">每行输入一个选项</p>
//                 </div>
//                 <div className="flex items-center space-x-2">
//                     <input
//                         type="checkbox"
//                         name="required"
//                         onChange={onChange}
//                         checked={focusItem?.required || false}
//                         className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
//                     />
//                     <label className="text-sm font-medium text-gray-700">必填字段</label>
//                 </div>
//             </div>
//         </div>
//     ),

//     radio: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>单选框属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">选项配置</label>
//                     <textarea
//                         name="options"
//                         onChange={onChange}
//                         value={focusItem?.options || '选项1\n选项2\n选项3'}
//                         rows="4"
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="每行一个选项"
//                     />
//                     <p className="text-xs text-gray-500 mt-1">每行输入一个选项</p>
//                 </div>
//                 <div className="flex items-center space-x-2">
//                     <input
//                         type="checkbox"
//                         name="required"
//                         onChange={onChange}
//                         checked={focusItem?.required || false}
//                         className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
//                     />
//                     <label className="text-sm font-medium text-gray-700">必填字段</label>
//                 </div>
//             </div>
//         </div>
//     ),

//     checkbox: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>复选框属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">选项配置</label>
//                     <textarea
//                         name="options"
//                         onChange={onChange}
//                         value={focusItem?.options || '选项1\n选项2\n选项3'}
//                         rows="4"
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="每行一个选项"
//                     />
//                     <p className="text-xs text-gray-500 mt-1">每行输入一个选项</p>
//                 </div>
//             </div>
//         </div>
//     ),

//     date: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>日期选择器属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">日期格式</label>
//                     <select
//                         name="format"
//                         onChange={onChange}
//                         value={focusItem?.format || 'YYYY-MM-DD'}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                     >
//                         <option value="YYYY-MM-DD">年-月-日</option>
//                         <option value="YYYY/MM/DD">年/月/日</option>
//                         <option value="DD/MM/YYYY">日/月/年</option>
//                         <option value="MM/DD/YYYY">月/日/年</option>
//                     </select>
//                 </div>
//                 <div className="flex items-center space-x-2">
//                     <input
//                         type="checkbox"
//                         name="required"
//                         onChange={onChange}
//                         checked={focusItem?.required || false}
//                         className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
//                     />
//                     <label className="text-sm font-medium text-gray-700">必填字段</label>
//                 </div>
//             </div>
//         </div>
//     ),

//     file_upload: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>文件上传属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">允许的文件类型</label>
//                     <input
//                         name="accept"
//                         onChange={onChange}
//                         value={focusItem?.accept || '*'}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="例如: .pdf,.doc,.docx"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">最大文件大小(MB)</label>
//                     <input
//                         type="number"
//                         name="maxSize"
//                         onChange={onChange}
//                         value={focusItem?.maxSize || 10}
//                         min="1"
//                         max="100"
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                     />
//                 </div>
//                 <div className="flex items-center space-x-2">
//                     <input
//                         type="checkbox"
//                         name="required"
//                         onChange={onChange}
//                         checked={focusItem?.required || false}
//                         className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
//                     />
//                     <label className="text-sm font-medium text-gray-700">必填字段</label>
//                 </div>
//             </div>
//         </div>
//     ),

//     image_upload: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>图片上传属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">字段名称</label>
//                     <input
//                         name="name"
//                         onChange={onChange}
//                         value={focusItem?.name || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="字段名称"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">显示标签</label>
//                     <input
//                         name="label"
//                         onChange={onChange}
//                         value={focusItem?.label || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="显示标签"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">图片尺寸限制</label>
//                     <div className="grid grid-cols-2 gap-2">
//                         <input
//                             type="number"
//                             name="maxWidth"
//                             onChange={onChange}
//                             value={focusItem?.maxWidth || 800}
//                             placeholder="最大宽度"
//                             className="px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         />
//                         <input
//                             type="number"
//                             name="maxHeight"
//                             onChange={onChange}
//                             value={focusItem?.maxHeight || 600}
//                             placeholder="最大高度"
//                             className="px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         />
//                     </div>
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">最大文件大小(MB)</label>
//                     <input
//                         type="number"
//                         name="maxSize"
//                         onChange={onChange}
//                         value={focusItem?.maxSize || 5}
//                         min="1"
//                         max="20"
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                     />
//                 </div>
//                 <div className="flex items-center space-x-2">
//                     <input
//                         type="checkbox"
//                         name="required"
//                         onChange={onChange}
//                         checked={focusItem?.required || false}
//                         className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
//                     />
//                     <label className="text-sm font-medium text-gray-700">必填字段</label>
//                 </div>
//             </div>
//         </div>
//     ),

//     div: ({ onChange, focusItem }) => (
//         <div className='w-full space-y-4'>
//             <div className='text-lg font-semibold text-gray-800 border-b pb-2'>容器属性</div>

//             <div className="space-y-3">
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">列数 (1-12)</label>
//                     <input
//                         type="number"
//                         name="width"
//                         onChange={onChange}
//                         value={focusItem?.width || 1}
//                         min="1"
//                         max="12"
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="列数"
//                     />
//                     <p className="text-xs text-gray-500 mt-1">设置为12时将占满整行</p>
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">高度</label>
//                     <input
//                         name="height"
//                         onChange={onChange}
//                         value={focusItem?.height || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="例如: 100px, 50vh"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">内边距</label>
//                     <input
//                         name="padding"
//                         onChange={onChange}
//                         value={focusItem?.padding || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="例如: 16px, 1rem"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">外边距</label>
//                     <input
//                         name="margin"
//                         onChange={onChange}
//                         value={focusItem?.margin || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="例如: 8px, 0.5rem"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">背景颜色</label>
//                     <input
//                         type="color"
//                         name="backgroundColor"
//                         onChange={onChange}
//                         value={focusItem?.backgroundColor || '#ffffff'}
//                         className="w-full h-10 px-1 py-1 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">圆角</label>
//                     <input
//                         name="borderRadius"
//                         onChange={onChange}
//                         value={focusItem?.borderRadius || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="例如: 4px, 0.25rem"
//                     />
//                 </div>
//                 <div>
//                     <label className="block text-sm font-medium text-gray-700 mb-1">边框</label>
//                     <input
//                         name="border"
//                         onChange={onChange}
//                         value={focusItem?.border || ''}
//                         className="w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
//                         placeholder="例如: 1px solid #ccc"
//                     />
//                 </div>
//             </div>
//         </div>
//     )
// }
