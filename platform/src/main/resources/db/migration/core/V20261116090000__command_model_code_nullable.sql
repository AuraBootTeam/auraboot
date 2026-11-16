-- 路由级命令(dynamic.*)无单模型归属:model_code 允许 NULL。
-- 授权与审计按调用时目标模型校验(DataAccessFilter/ModelPermission),定义行不参与;
-- 记录级命令的 modelCode 必填由 manifest 导入校验强制(PluginManifestValidator)。
-- 平台先例:ab_page_schema(2026-05-20)、ab_automation(2026-06-05)同款放宽。
ALTER TABLE ab_command_definition ALTER COLUMN model_code DROP NOT NULL;
COMMENT ON COLUMN ab_command_definition.model_code IS '目标模型;路由级命令(dynamic.*)无单模型归属,为 NULL——授权按调用时目标模型校验';
