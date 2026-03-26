# 表单 增删改查

{
  "version": "1.0.0",
  "type": "form",
  "id": 1,
  "name": "Complete Component Form Example",
  "theme": "auto",
  "description": "Complete form configuration example based on basic_components directory",
  "layout": {
    "type": "grid",
    "columns": 2,
    "mode": "fixed"
  },
  "components": [
    {
      "id": "input_1",
      "type": "input",
      "props": {
        "name": "username",
        "label": "Username",
        "placeholder": "Please enter username",
        "type": "text",
        "size": "medium",
        "variant": "default",
        "inline": true,
        "autoComplete": "username",
        "maxLength": 50
      },

      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Username cannot be empty"
        },
        {
          "type": "minLength",
          "value": "3",
          "message": "Username must be at least {value} characters"
        },
        {
          "type": "pattern",
          "value": "^[a-zA-Z0-9_]+$",
          "message": "Username can only contain letters, numbers and underscores"
        }
      ]
    },
    {
      "id": "456",
      "type": "select",
      "props": {
        "name": "fruit",
        "multiple": false,
        "dataSourceId": "1"
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "cant be empty"
        }
      ]
    },
    {
      "id": "input_2",
      "type": "input",
      "props": {
        "name": "email",
        "label": "Email",
        "placeholder": "Please enter email address",
        "type": "email",
        "size": "medium",
        "variant": "default",
        "inline": true,
        "autoComplete": "email"
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Email cannot be empty"
        },
        {
          "type": "email",
          "value": "true",
          "message": "Please enter a valid email address"
        }
      ],
      "layout": {
        "span": 1
      }
    },
    {
      "id": "textarea_1",
      "type": "textarea",
      "props": {
        "name": "description",
        "label": "Personal Bio",
        "placeholder": "Please enter personal bio",
        "rows": 4,
        "maxLength": 500,
        "showCount": true,
        "resize": "vertical",
        "size": "medium",
        "variant": "default",
        "inline": false
      },
      "validations": [
        {
          "type": "maxLength",
          "value": "500",
          "message": "Personal bio cannot exceed 500 characters"
        }
      ],
      "layout": {
        "span": 2
      }
    },
    {
      "id": "select_1",
      "type": "select",
      "props": {
        "name": "country",
        "label": "Country",
        "placeholder": "Please select country",
        "options": [
          {"code": "cn", "value": "china", "label": "China"},
          {"code": "us", "value": "usa", "label": "United States"},
          {"code": "jp", "value": "japan", "label": "Japan"},
          {"code": "kr", "value": "korea", "label": "South Korea"}
        ],
        "size": "medium",
        "variant": "default",
        "clearable": true,
        "inline": true
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Please select a country"
        }
      ],
      "layout": {
        "width": "75%",
        "customWidth": true
      }
    },
    {
      "id": "multiselect_1",
      "type": "multiselect",
      "props": {
        "name": "skills",
        "label": "Skills",
        "placeholder": "Please select your skills",
        "options": [

          {"code": "js", "value": "javascript", "label": "JavaScript"},
          {"code": "ts", "value": "typescript", "label": "TypeScript"},
          {"code": "py", "value": "python", "label": "Python"},
          {"code": "java", "value": "java", "label": "Java"},
          {"code": "go", "value": "go", "label": "Go"},
          {"code": "rust", "value": "rust", "label": "Rust"}
        ],
        "maxSelection": 5,
        "searchable": true,
        "size": "medium",
        "variant": "default",
        "clearable": true,
        "inline": true
      },
      "validations": [
        {
          "type": "minItems",
          "value": "1",
          "message": "Please select at least one skill"
        }
      ],
      "layout": {
        "span": 1
      }
    },
    {
      "id": "checkbox_1",
      "type": "checkbox",
      "props": {
        "name": "agreements",
        "label": "Agreement Terms",
        "options": [
          {"code": "privacy", "value": "privacy", "label": "I have read and agree to the Privacy Policy"},
          {"code": "terms", "value": "terms", "label": "I have read and agree to the Terms of Service"},
          {"code": "newsletter", "value": "newsletter", "label": "Subscribe to product update emails"}
        ],
        "direction": "vertical",
        "checkAll": false,
        "size": "medium",
        "variant": "default",
        "inline": false
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Please agree to the necessary terms"
        }
      ],
      "layout": {
        "span": 2
      }
    },
    {
      "id": "radio_1",
      "type": "radio",
      "props": {
        "name": "gender",
        "label": "Gender",
        "options": [
          {"code": "male", "value": "male", "label": "Male"},
          {"code": "female", "value": "female", "label": "Female"},
          {"code": "other", "value": "other", "label": "Other"}
        ],
        "direction": "horizontal",
        "size": "medium",
        "variant": "default",
        "inline": true
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Please select gender"
        }
      ],
      "layout": {
        "span": 1
      }
    },
    {
      "id": "treeselect_1",
      "type": "treeselect",
      "props": {
        "name": "department",
        "label": "Department",
        "placeholder": "Please select department",
        "treeData": [
          {
            "code": "tech",
            "value": "tech",
            "label": "Technology Department",
            "children": [
              {"code": "frontend", "value": "frontend", "label": "Frontend Team"},
              {"code": "backend", "value": "backend", "label": "Backend Team"},
              {"code": "mobile", "value": "mobile", "label": "Mobile Team"},
              {"code": "devops", "value": "devops", "label": "DevOps Team"}
            ]
          },
          {
            "code": "product",
            "value": "product",
            "label": "Product Department",
            "children": [
              {"code": "design", "value": "design", "label": "Design Team"},
              {"code": "pm", "value": "pm", "label": "Product Team"},
              {"code": "research", "value": "research", "label": "User Research Team"}
            ]
          },
          {
            "code": "business",
            "value": "business",
            "label": "Business Department",
            "children": [
              {"code": "sales", "value": "sales", "label": "Sales Team"},
              {"code": "marketing", "value": "marketing", "label": "Marketing Team"}
            ]
          }
        ],
        "multiple": false,
        "checkable": false,
        "searchable": true,
        "size": "medium",
        "variant": "default",
        "clearable": true,
        "inline": true
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Please select a department"
        }
      ],
      "layout": {
        "span": 1
      }
    },
    {
      "id": "fileupload_1",
      "type": "fileupload",
      "props": {
        "name": "resume",
        "label": "Resume Upload",
        "accept": ".pdf,.doc,.docx",
        "multiple": false,
        "maxSize": 10485760,
        "maxCount": 1,
        "uploadUrl": "/upload",
        "listType": "text",
        "showUploadList": true,
        "size": "medium",
        "variant": "default",
        "inline": false
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Please upload resume"
        }
      ],
      "layout": {
        "span": 1
      }
    },
    {
      "id": "fileupload_2",
      "type": "fileupload",
      "props": {
        "name": "attachments",
        "label": "Attachments Upload",
        "accept": ".pdf,.doc,.docx,.jpg,.png,.zip",
        "multiple": true,
        "maxSize": 5242880,
        "maxCount": 5,
        "uploadUrl": "/upload",
        "listType": "picture-card",
        "showUploadList": true,
        "size": "medium",
        "variant": "default",
        "inline": false
      },
      "layout": {
        "span": 1
      }
    },
    {
      "id": "date_1",
      "type": "date",
      "props": {
        "name": "birthDate",
        "label": "Birth Date",
        "placeholder": "Please select birth date",
        "format": "YYYY-MM-DD",
        "showTime": false,
        "disabledDate": "future",
        "showToday": true,
        "clearable": true,
        "size": "medium",
        "variant": "default",
        "inline": true
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Please select birth date"
        }
      ],
      "layout": {
        "span": 1
      }
    },
    {
      "id": "datepicker_1",
      "type": "datepicker",
      "props": {
        "name": "joinDate",
        "label": "Join Date",
        "placeholder": "Please select join date",
        "type": "date",
        "min": "2020-01-01",
        "max": "2030-12-31",
        "step": 1,
        "clearable": true,
        "size": "medium",
        "variant": "default",
        "inline": true
      },
      "validations": [
        {
          "type": "required",
          "value": "true",
          "message": "Please select join date"
        }
      ],
      "layout": {
        "span": 1
      }
    },
    {
      "id": "image_1",
      "type": "image",
      "props": {
        "name": "avatar",
        "label": "Avatar",
        "alt": "User avatar",
        "width": 100,
        "height": 100,
        "shape": "circle",
        "fit": "cover",
        "preview": true,
        "size": "medium",
        "variant": "default",
        "inline": true
      },
      "layout": {
        "span": 1
      }
    },

  {
  "id": "cascade_select_1",
  "type": "cascadeSelect",
  "props": {
    "name": "address",
    "label": "Address",
    "placeholder": "Please select address",
    "levels": [
      {
        "name": "province",
        "label": "Province",
        "placeholder": "Select province"
      },
      {
        "name": "city",
        "label": "City",
        "placeholder": "Select city"
      },
      {
        "name": "district",
        "label": "District",
        "placeholder": "Select district"
      }
    ],
    "options": [
      {
        "value": "beijing",
        "label": "Beijing",
        "children": [
          {
            "value": "dongcheng",
            "label": "Dongcheng District",
            "children": [
              { "value": "wangfujing", "label": "Wangfujing Street" },
              { "value": "jingshan", "label": "Jingshan Street" }
            ]
          },
          {
            "value": "xicheng",
            "label": "Xicheng District",
            "children": [
              { "value": "xinjiekou", "label": "Xinjiekou Street" },
              { "value": "xisi", "label": "Xisi Street" }
            ]
          },
          {
            "value": "chaoyang",
            "label": "Chaoyang District",
            "children": [
              { "value": "sanlitun", "label": "Sanlitun Street" },
              { "value": "wangjing", "label": "Wangjing Street" }
            ]
          }
        ]
      },
      {
        "value": "shanghai",
        "label": "Shanghai",
        "children": [
          {
            "value": "huangpu",
            "label": "Huangpu District",
            "children": [
              { "value": "nanjingdong", "label": "Nanjing East Road" },
              { "value": "yuyuan", "label": "Yu Garden" }
            ]
          },
          {
            "value": "pudong",
            "label": "Pudong New Area",
            "children": [
              { "value": "lujiazui", "label": "Lujiazui" },
              { "value": "jinqiao", "label": "Jinqiao" }
            ]
          }
        ]
      }

    ],
    "layout": "horizontal",
    "showAllLevels": true,
    "changeOnSelect": false,
    "clearable": true,
    "size": "medium",
    "variant": "default",
    "inline": false
  },
  "validations": [
    {
      "type": "required",
      "value": "true",
      "message": "Please select address"
    }
  ],
  "layout": {
    "span": 1
  }
}

  ],
  "actions": [
    {
      "type": "submit",
      "value": "create",
      "label": "Submit",
      "visible": ["create", "edit"],
      "variant": "primary"

    },
    {
      "type": "submit",
      "value": "draft",
      "label": "Save Draft",
      "visible": ["create", "edit"],
      "variant": "secondary"
    },
    {
      "type": "reset",
      "value": "reset",
      "label": "Reset Form",
      "visible": ["create"],
      "variant": "outline"
    },
    {
      "type": "button",
      "value": "return",
      "label": "Return",
      "visible": [ "edit", "view"],
      "variant": "outline",
      "returnPath": "-1"

    }
  ],
  "formConfig": {
    "autoSaveInterval":60000,

     "submitOnEnter": true,

    "validateOnChange": true,
    "validateOnBlur": true,

    "showErrorSummary": true,
    "scrollToError": true
  },

  "props": {

  }
}


# 列表查询


{
   "id": "test",
   "version": "1.0.0",
   "type": "list",
   "name": "SchemaList",
   "dataSourceId": "2",
   "compareConditions": [
     {
       "name": "type",
       "operators": [
         "like"
       ],
       "component":"input"
     },
     {
       "name": "deletedFlag",
       "operators": [
         "eq"
       ],
       "dataSourceId": "1",
       "component":"select"
     },
     {
       "name": "createdAt",
       "operators": [
         "gte",
         "gt",
         "lte"
       ],
       "component":"date"
     }
   ],
   "columns": [
     {
       "name": "type",
       "sortable": true,
       "filterable": true
     },
     {
       "name": "id",
       "sortable": false,
       "filterable": false,
       "visible": false
     }
     ,
     {
       "name": "detail",
       "props": {"target": "/ticket/${row.ticket_id}"}
     }
   ],
   "pagination": {
     "pageSizeOptions": [
       10,
       15
     ]
   },
   "actions": [
    {
          "type": "submit",
          "value": "query",
          "label":"Query"
     },
     {
           "type": "reset",
           "value": "reset",
           "label":"Reset"
     }
   ],
   "props": {}
 }