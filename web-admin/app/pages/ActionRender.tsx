// import React from "react";

// import { SubmitButton } from "./old_just_backup_basic_components/SmartButton";

// interface ActionConfig {
//   key?: string | number;
//   [key: string]: any;
// }

// export default function ActionRender(config: ActionConfig, count: number) {

//   let props = {
//     ...config,
//     key: config.key || count,
//     label: config.label || '提交',
//     type: config.type || 'submit'
//   };
//   // console.dir(config, { depth: null, colors: true });

//   let type = SubmitButton;

//   if (typeof type !== "undefined") {

//     //React.createElement function takes in three arguments: type, props, and children.
//     return React.createElement(
//       type,
//       props
//     );
//   } else {

//     console.log("The component has not been created yet");
//   }

//   return null;
// }
