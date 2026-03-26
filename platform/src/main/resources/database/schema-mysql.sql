-- =============================================================
-- MySQL 8.0+ Schema (auto-generated from schema-postgresql.sql)
-- Differences from PostgreSQL:
--   JSONB  → JSON
--   UUID   → VARCHAR(36)
--   BIGSERIAL → BIGINT AUTO_INCREMENT
--   Partial indexes (WHERE clause) removed
--   COMMENT ON ... removed
--   gen_random_uuid() → UUID()
--   pg_trgm extension removed
-- =============================================================



create TABLE IF NOT EXISTS ns_user
             (
                          id                     BIGINT PRIMARY KEY,
                          pid                    VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
                          created_at             TIMESTAMP,
                          updated_at             TIMESTAMP,
                          user_name              VARCHAR(64),
                          nick_name              VARCHAR(64),
                          mobile                 VARCHAR(64),
                          email                  VARCHAR(64),
                          password               VARCHAR(256),
                          salt                   VARCHAR(64),
                          reset_password_token   VARCHAR(255),
                          reset_password_sent_at TIMESTAMP,
                          remember_created_at    TIMESTAMP,
                          sign_in_count          BIGINT,
                          current_sign_in_at     TIMESTAMP,
                          last_sign_in_at        TIMESTAMP,
                          is_enabled             BOOLEAN DEFAULT TRUE,
                          is_account_non_expired BOOLEAN DEFAULT TRUE,
                          is_account_non_locked  BOOLEAN DEFAULT TRUE,
                          is_credentials_non_expired BOOLEAN DEFAULT TRUE,
                          area                   VARCHAR(512),
                          signature              VARCHAR(512),
                          img_id                 VARCHAR(128)
             );

CREATE UNIQUE INDEX user_name_idx ON ns_user (user_name);
CREATE UNIQUE INDEX email_idx ON ns_user (email);
CREATE UNIQUE INDEX idx_ns_user_pid ON ns_user(pid);

-- 下面3个表字段完全一样， 索引一样，data表字段需要动态建立

create TABLE IF NOT EXISTS ns_dict(
  id BIGINT PRIMARY KEY,
  pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
  tenant_id int NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,

  deleted_status SMALLINT DEFAULT 0, -- 0 normal, other:not normal

  row_id VARCHAR(36) NOT NULL, -- similar as uber row_key
  ref_row_id VARCHAR(36), -- many to one and so on, relation mapping
  type varchar(64) NOT NULL, -- similar as table name or entity class
  data JSON NOT NULL
);

CREATE INDEX type_idx ON ns_dict(type);
CREATE INDEX ref_row_id_idx ON ns_dict(ref_row_id);
CREATE UNIQUE INDEX row_id_uk ON ns_dict(row_id);
CREATE UNIQUE INDEX idx_ns_dict_pid ON ns_dict(pid);


create TABLE IF NOT EXISTS ns_schema(
  id BIGINT PRIMARY KEY,
  pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
  tenant_id int NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,

  deleted_status SMALLINT DEFAULT 0, -- 0 normal, other:not normal

  row_id VARCHAR(36) NOT NULL, -- similar as uber row_key
  ref_row_id VARCHAR(36), -- many to one and so on, relation mapping
  type varchar(64) NOT NULL, -- similar as table name or entity class
  data JSON NOT NULL
);

CREATE INDEX ns_schema_type_idx ON ns_schema(type);
CREATE INDEX ns_schema_ref_row_id_idx ON ns_schema(ref_row_id);
CREATE UNIQUE INDEX ns_schema_row_id_uk ON ns_schema(row_id);
CREATE UNIQUE INDEX idx_ns_schema_pid ON ns_schema(pid);


create TABLE IF NOT EXISTS ns_data_source(
  id BIGINT PRIMARY KEY,
  pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
  tenant_id int NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,

  deleted_status SMALLINT DEFAULT 0, -- 0 normal, other:not normal
  method varchar(64) NOT NULL,

  row_id VARCHAR(36) NOT NULL, -- similar as uber row_key
  ref_row_id VARCHAR(36), -- many to one and so on, relation mapping
  type varchar(64) NOT NULL, -- similar as table name or entity class
  data JSON NOT NULL
);

CREATE INDEX ns_data_source_type_idx ON ns_data_source(type);
CREATE INDEX ns_data_source_ref_row_id_idx ON ns_data_source(ref_row_id);
CREATE UNIQUE INDEX ns_data_source_row_id_uk ON ns_data_source(row_id);
CREATE UNIQUE INDEX idx_ns_data_source_pid ON ns_data_source(pid);


create TABLE IF NOT EXISTS ns_instance(
  id BIGINT PRIMARY KEY,
  pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
  tenant_id int NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,

  deleted_status SMALLINT DEFAULT 0, -- 0 normal, other:not normal

  row_id VARCHAR(36) NOT NULL, -- similar as uber row_key
  ref_row_id VARCHAR(36), -- many to one and so on, relation mapping
  type varchar(64) NOT NULL, -- similar as table name or entity class
  data JSON NOT NULL
);

CREATE INDEX ns_instance_type_idx ON ns_instance(type);
CREATE INDEX ns_instance_ref_row_id_idx ON ns_instance(ref_row_id);
CREATE UNIQUE INDEX ns_instance_row_id_uk ON ns_instance(row_id);
CREATE UNIQUE INDEX idx_ns_instance_pid ON ns_instance(pid);



-- 创建文件信息表
CREATE TABLE ns_files (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    file_name VARCHAR(255) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    file_extension VARCHAR(10),
    storage_type VARCHAR(10) NOT NULL,
    local_path VARCHAR(500),
    cloud_path VARCHAR(1000),
    cloud_bucket VARCHAR(100),
    cloud_key VARCHAR(500),
    cloud_region VARCHAR(50),
    upload_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(36),
    status VARCHAR(10) DEFAULT 'active',
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_flag BOOLEAN DEFAULT FALSE
);

-- 添加表注释

-- 添加列注释

-- 创建索引
CREATE INDEX idx_ns_files_created_by ON ns_files (created_by);
CREATE INDEX idx_ns_files_storage_type ON ns_files (storage_type);
CREATE INDEX idx_ns_files_status ON ns_files (status);
CREATE INDEX idx_ns_files_upload_time ON ns_files (upload_time);
CREATE INDEX idx_ns_files_created_time ON ns_files (created_time);
CREATE UNIQUE INDEX idx_ns_files_pid ON ns_files(pid);


-- ========================================
-- UMGT 用户管理模块数据表
-- ========================================

-- 租户表
CREATE TABLE ns_tenant (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(100),
    logo VARCHAR(800),
    industry VARCHAR(50),
    
    contact_email VARCHAR(100),
    contact_phone VARCHAR(20),
    website VARCHAR(800),
    
    status VARCHAR(20) DEFAULT 'active',
    
    description TEXT,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT
);

CREATE INDEX idx_ns_tenant_status ON ns_tenant (status);
CREATE INDEX idx_ns_tenant_name ON ns_tenant (name);
CREATE INDEX idx_ns_tenant_created_at ON ns_tenant (created_at);
CREATE UNIQUE INDEX idx_ns_tenant_pid ON ns_tenant(pid);

-- 地址表
CREATE TABLE ns_address (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    
    tenant_id BIGINT NOT NULL,
    
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- 国家信息
    country_name VARCHAR(50),
    country_code VARCHAR(10),
    
    -- 省份信息
    province_name VARCHAR(50),
    province_code VARCHAR(12),
    
    -- 城市信息
    city_name VARCHAR(50),
    city_code VARCHAR(12),
    
    -- 区县信息
    district_name VARCHAR(50),
    district_code VARCHAR(12),
    
    -- 街道信息
    street_name VARCHAR(200),
    street_code VARCHAR(12),
    
    -- 详细地址
    detail_address TEXT,
    
    -- 邮政编码
    postal_code VARCHAR(20),
    
    status VARCHAR(20) DEFAULT 'active',
    
    is_default BOOLEAN DEFAULT FALSE,
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,

    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);


-- 创建索引
CREATE INDEX idx_ns_address_status ON ns_address (status);
CREATE UNIQUE INDEX idx_ns_address_pid ON ns_address(pid);
CREATE INDEX idx_ns_address_tenant_id ON ns_address (tenant_id);
CREATE INDEX idx_ns_address_province_code ON ns_address (province_code);
CREATE INDEX idx_ns_address_city_code ON ns_address (city_code);
CREATE INDEX idx_ns_address_district_code ON ns_address (district_code);
CREATE INDEX idx_ns_address_street_code ON ns_address (street_code);
CREATE INDEX idx_ns_address_is_default ON ns_address (is_default);

-- 门店表
CREATE TABLE ns_store (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT NOT NULL,
    address_id BIGINT,
    
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50),
    type VARCHAR(20),

    extension JSON,
    
    status VARCHAR(20) DEFAULT 'active',
    open_date DATE,
    close_date DATE,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id),
    FOREIGN KEY (address_id) REFERENCES ns_address(id)
);

CREATE INDEX idx_ns_store_tenant_id ON ns_store (tenant_id);
CREATE INDEX idx_ns_store_status ON ns_store (status);
CREATE INDEX idx_ns_store_code ON ns_store (code);
CREATE UNIQUE INDEX idx_ns_store_pid ON ns_store(pid);

-- 租户成员表
CREATE TABLE ns_tenant_member (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    tenant_id BIGINT NOT NULL,
    user_id BIGINT,

    status VARCHAR(20) DEFAULT 'active',


    join_date DATE,
    leave_date DATE,

    permissions JSON,
    settings JSON,
    extensions JSON,

    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,

    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);

CREATE INDEX idx_ns_tenant_member_tenant_id ON ns_tenant_member (tenant_id);
CREATE INDEX idx_ns_tenant_member_user_id ON ns_tenant_member (user_id);
CREATE INDEX idx_ns_tenant_member_status ON ns_tenant_member (status);
CREATE UNIQUE INDEX idx_ns_tenant_member_unique ON ns_tenant_member (tenant_id, user_id) ;
CREATE UNIQUE INDEX idx_ns_tenant_member_pid ON ns_tenant_member(pid);

-- 角色表
CREATE TABLE ns_role (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL ,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT,
    
    name VARCHAR(50) NOT NULL,
    code VARCHAR(100),

    description TEXT,
    
    type VARCHAR(20) DEFAULT 'custom',
    scope_content JSON,
    scope_type VARCHAR(20),
    
    priority INTEGER DEFAULT 100,
    status VARCHAR(20) DEFAULT 'active',
    
    is_default BOOLEAN DEFAULT FALSE,
    is_system BOOLEAN DEFAULT FALSE,
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);

CREATE INDEX idx_ns_role_tenant_id ON ns_role (tenant_id);
CREATE INDEX idx_ns_role_status ON ns_role (status);
CREATE INDEX idx_ns_role_type ON ns_role (type);
CREATE UNIQUE INDEX idx_ns_role_pid ON ns_role(pid);

-- 权限表
CREATE TABLE ns_permission (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL ,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    name VARCHAR(50) NOT NULL,
    code VARCHAR(100) NOT NULL,
    description TEXT,
    
    module VARCHAR(20),
    resource VARCHAR(100),
    action VARCHAR(20),
    
    type VARCHAR(20) DEFAULT 'api',
    parent_id BIGINT,
    path VARCHAR(500),
    
    sort_order INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',
    
    is_system BOOLEAN DEFAULT FALSE,
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT
    
);

CREATE UNIQUE INDEX idx_ns_permission_code ON ns_permission (code);
CREATE INDEX idx_ns_permission_module ON ns_permission (module);
CREATE INDEX idx_ns_permission_type ON ns_permission (type);
CREATE INDEX idx_ns_permission_parent_id ON ns_permission (parent_id);
CREATE UNIQUE INDEX idx_ns_permission_pid ON ns_permission(pid);

-- 角色权限关联表
CREATE TABLE ns_role_permission (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    
    grant_type VARCHAR(10) DEFAULT 'grant',
    conditions TEXT,
    
    effective_date DATE,
    expiry_date DATE,
    
    status VARCHAR(20) DEFAULT 'active',
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (role_id) REFERENCES ns_role(id),
    FOREIGN KEY (permission_id) REFERENCES ns_permission(id)
);

CREATE INDEX idx_ns_role_permission_role_id ON ns_role_permission (role_id);
CREATE INDEX idx_ns_role_permission_permission_id ON ns_role_permission (permission_id);
CREATE UNIQUE INDEX idx_ns_role_permission_unique ON ns_role_permission (role_id, permission_id) ;
CREATE UNIQUE INDEX idx_ns_role_permission_pid ON ns_role_permission(pid);

-- 用户角色关联表
CREATE TABLE ns_user_role (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    user_id VARCHAR(36) NOT NULL,
    tenant_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,

    
    assign_type VARCHAR(20) DEFAULT 'direct',

    effective_date DATE,
    expiry_date DATE,
    
    status VARCHAR(20) DEFAULT 'active',
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,

    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id),
    FOREIGN KEY (role_id) REFERENCES ns_role(id)
);

CREATE INDEX idx_ns_user_role_user_id ON ns_user_role (user_id);
CREATE INDEX idx_ns_user_role_role_id ON ns_user_role (role_id);
CREATE UNIQUE INDEX idx_ns_user_role_pid ON ns_user_role(pid);

-- 邀请表
CREATE TABLE ns_invitation (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT NOT NULL,

    inviter_user_id BIGINT NOT NULL,

    
    invite_code VARCHAR(32) NOT NULL,


    message TEXT,
    
    status VARCHAR(20) DEFAULT 'pending',

    expired_at TIMESTAMP,
    

    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);

CREATE UNIQUE INDEX idx_ns_invitation_code ON ns_invitation (invite_code);
CREATE INDEX idx_ns_invitation_tenant_id ON ns_invitation (tenant_id);
CREATE INDEX idx_ns_invitation_status ON ns_invitation (status);
CREATE UNIQUE INDEX idx_ns_invitation_pid ON ns_invitation(pid);

CREATE TABLE ns_menu (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    tenant_id BIGINT,


    parent_id BIGINT,                    -- 父菜单ID
    name VARCHAR(100) NOT NULL,          -- 菜单名称
    path VARCHAR(500),                   -- 路由路径
    component VARCHAR(200),              -- 前端组件路径
    icon VARCHAR(100),                   -- 图标

    type INT NOT NULL DEFAULT 1,     -- 目录=0，菜单=1，按钮=2
    permission_code VARCHAR(100),        -- 关联的权限编码

    visible BOOLEAN DEFAULT TRUE,        -- 是否展示
    order_no INTEGER DEFAULT 0,          -- 排序号

    -- 扩展字段
    i18n_key VARCHAR(100),               -- 国际化key
    redirect VARCHAR(500),               -- 重定向路径
    extension JSON ,

    status VARCHAR(20) DEFAULT 'active',
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,

    FOREIGN KEY (parent_id) REFERENCES ns_menu(id),
    FOREIGN KEY (permission_code) REFERENCES ns_permission(code)
);

CREATE INDEX idx_ns_menu_parent_id ON ns_menu (parent_id);
CREATE INDEX idx_ns_menu_permission_code ON ns_menu (permission_code);
CREATE INDEX idx_ns_menu_type ON ns_menu (type);
CREATE INDEX idx_ns_menu_order_no ON ns_menu (order_no);
CREATE UNIQUE INDEX idx_ns_menu_pid ON ns_menu(pid);


-- 行政区划表
CREATE TABLE ns_administrative_division (
    id BIGINT PRIMARY KEY,                          -- 主键ID
    code VARCHAR(12) NOT NULL,                      -- 行政区划代码（如：110000、110100、110101）
    name VARCHAR(100) NOT NULL,                     -- 行政区划名称（如：北京市、市辖区、东城区）
    parent_code VARCHAR(12),                        -- 父级行政区划代码
    level SMALLINT NOT NULL,                        -- 行政级别（1:省级，2:市级，3:区县级，4:街道级，5:社区级）
    sort_order INT DEFAULT 0,                       -- 排序字段

    status VARCHAR(20) DEFAULT 'active',            -- 状态（active:启用，inactive:禁用）
    deleted_flag BOOLEAN DEFAULT FALSE,             -- 逻辑删除标记

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, -- 创建时间
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP  -- 更新时间
);

-- 添加表注释

-- 添加字段注释

-- 创建索引
-- 1. 行政区划代码唯一索引（业务主键）
CREATE UNIQUE INDEX idx_admin_division_code ON ns_administrative_division(code) ;

-- 2. 父级代码索引（用于查询下级区划）
CREATE INDEX idx_admin_division_parent_code ON ns_administrative_division(parent_code) ;

-- 3. 级别索引（用于按级别查询）
CREATE INDEX idx_admin_division_level ON ns_administrative_division(level) ;

-- 4. 复合索引：父级代码+级别+排序（用于获取某个区划的下级列表）
CREATE INDEX idx_admin_division_parent_level_sort ON ns_administrative_division(parent_code, level, sort_order) ;

-- 5. 状态索引（用于查询启用的区划）
CREATE INDEX idx_admin_division_status ON ns_administrative_division(status) ;

-- 6. 名称索引（用于模糊搜索）

-- 7. 创建时间索引（用于按时间范围查询）
CREATE INDEX idx_admin_division_created_at ON ns_administrative_division(created_at);

-- 添加外键约束（自引用）
--ALTER TABLE ns_administrative_division
--ADD CONSTRAINT fk_admin_division_parent
--FOREIGN KEY (parent_code)
--REFERENCES ns_administrative_division(code)
--DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX idx_admin_division_name ON ns_administrative_division USING gin(name gin_trgm_ops);

ALTER TABLE ns_administrative_division ADD CONSTRAINT chk_admin_division_status CHECK (status IN ('active', 'inactive'));

-- 引入数字标牌系统表结构
-- ========================================
-- 数字标牌系统核心数据表
-- ========================================

-- 设备表
CREATE TABLE ns_device (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT NOT NULL,
    store_id BIGINT,
    
    device_name VARCHAR(100) NOT NULL,
    device_code VARCHAR(50) UNIQUE NOT NULL,
    device_type VARCHAR(20) DEFAULT 'TV',  -- TV, LED, TABLET
    
    -- 设备硬件信息
    mac_address VARCHAR(17),
    ip_address VARCHAR(15),
    screen_resolution VARCHAR(20),  -- 1920x1080
    screen_size DECIMAL(5,2),       -- 屏幕尺寸(英寸)
    orientation VARCHAR(10) DEFAULT 'landscape',  -- LANDSCAPE, PORTRAIT
    
    -- 设备状态
    status VARCHAR(20) DEFAULT 'offline',  -- ONLINE, OFFLINE, MAINTENANCE
    activation_status VARCHAR(20) DEFAULT 'inactive',  -- active, inactive, pending
    last_heartbeat TIMESTAMP,
    
    -- 位置信息
    location_description TEXT,
    floor_level VARCHAR(10),
    zone VARCHAR(50),
    
    -- 设备配置
    volume_level INTEGER DEFAULT 50,
    brightness_level INTEGER DEFAULT 80,
    auto_power_on TIME,
    auto_power_off TIME,
    
    -- 扩展信息
    hardware_info JSON,
    settings JSON,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id),
    FOREIGN KEY (store_id) REFERENCES ns_store(id)
);


CREATE INDEX idx_ns_device_tenant_id ON ns_device (tenant_id);
CREATE INDEX idx_ns_device_store_id ON ns_device (store_id);
CREATE INDEX idx_ns_device_status ON ns_device (status);
CREATE INDEX idx_ns_device_activation_status ON ns_device (activation_status);
CREATE UNIQUE INDEX idx_ns_device_code ON ns_device (device_code);
CREATE UNIQUE INDEX idx_ns_device_pid ON ns_device(pid);

-- 设备登录记录表
CREATE TABLE ns_device_login_record (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT,
    
    device_login_code VARCHAR(32) UNIQUE NOT NULL,
    device_code VARCHAR(50) NOT NULL,
    device_id BIGINT,
    
    user_id BIGINT,
    user_pid VARCHAR(26),
    
    status VARCHAR(20) DEFAULT 'pending',  -- pending, SCANNED, confirmed, success, expired, cancelled
    
    expires_at TIMESTAMP NOT NULL,
    scanned_at TIMESTAMP,
    confirmed_at TIMESTAMP,
    success_at TIMESTAMP,
    
    client_ip VARCHAR(45),
    user_agent TEXT,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id),
    FOREIGN KEY (device_id) REFERENCES ns_device(id),
    FOREIGN KEY (user_id) REFERENCES ns_user(id)
);


CREATE INDEX idx_ns_device_login_record_tenant_id ON ns_device_login_record (tenant_id);
CREATE INDEX idx_ns_device_login_record_device_code ON ns_device_login_record (device_code);
CREATE INDEX idx_ns_device_login_record_device_id ON ns_device_login_record (device_id);
CREATE INDEX idx_ns_device_login_record_user_id ON ns_device_login_record (user_id);
CREATE INDEX idx_ns_device_login_record_status ON ns_device_login_record (status);
CREATE INDEX idx_ns_device_login_record_expires_at ON ns_device_login_record (expires_at);
CREATE UNIQUE INDEX idx_ns_device_login_record_code ON ns_device_login_record (device_login_code);
CREATE UNIQUE INDEX idx_ns_device_login_record_pid ON ns_device_login_record(pid);

-- 内容表
CREATE TABLE ns_content (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT NOT NULL,
    
    content_name VARCHAR(100) NOT NULL,
    content_type VARCHAR(20) NOT NULL,  -- IMAGE, VIDEO, TEXT, HTML, URL
    
    -- 文件信息
    file_id BIGINT,
    file_url VARCHAR(500),
    file_size BIGINT,
    duration INTEGER,  -- 播放时长(秒)
    
    -- 内容属性
    width INTEGER,
    height INTEGER,
    mime_type VARCHAR(100),
    
    -- 文本内容
    text_content TEXT,
    html_content TEXT,
    
    -- 样式配置
    style_config JSON,
    
    -- 状态
    status VARCHAR(20) DEFAULT 'active',  -- active, inactive, draft
    
    -- 标签和分类
    tags VARCHAR(500),
    category VARCHAR(50),
    
    description TEXT,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id),
    FOREIGN KEY (file_id) REFERENCES ns_files(id)
);


CREATE INDEX idx_ns_content_tenant_id ON ns_content (tenant_id);
CREATE INDEX idx_ns_content_type ON ns_content (content_type);
CREATE INDEX idx_ns_content_status ON ns_content (status);
CREATE INDEX idx_ns_content_category ON ns_content (category);
CREATE UNIQUE INDEX idx_ns_content_pid ON ns_content(pid);

-- 节目表
CREATE TABLE ns_program (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT NOT NULL,
    
    program_name VARCHAR(100) NOT NULL,
    program_type VARCHAR(20) DEFAULT 'sequence',  -- SEQUENCE, LOOP, SCHEDULE
    
    -- 播放配置
    total_duration INTEGER,  -- 总时长(秒)
    loop_count INTEGER DEFAULT 0,  -- 循环次数，0表示无限循环
    
    -- 时间配置
    start_time TIME,
    end_time TIME,
    valid_from DATE,
    valid_to DATE,
    
    -- 播放规则
    play_rules JSON,
    
    -- 状态
    status VARCHAR(20) DEFAULT 'draft',  -- draft, published, archived
    
    description TEXT,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);


CREATE INDEX idx_ns_program_tenant_id ON ns_program (tenant_id);
CREATE INDEX idx_ns_program_status ON ns_program (status);
CREATE INDEX idx_ns_program_type ON ns_program (program_type);
CREATE UNIQUE INDEX idx_ns_program_pid ON ns_program(pid);

-- 节目内容关联表
CREATE TABLE ns_program_content (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    program_id BIGINT NOT NULL,
    content_id BIGINT NOT NULL,
    
    -- 播放顺序
    play_order INTEGER NOT NULL,
    
    -- 播放配置
    duration INTEGER,  -- 播放时长(秒)，覆盖内容默认时长
    transition_effect VARCHAR(20),  -- 转场效果
    
    -- 位置配置
    position_x INTEGER DEFAULT 0,
    position_y INTEGER DEFAULT 0,
    width INTEGER,
    height INTEGER,
    z_index INTEGER DEFAULT 1,
    
    -- 扩展配置
    config JSON,
    
    status VARCHAR(20) DEFAULT 'active',
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (program_id) REFERENCES ns_program(id),
    FOREIGN KEY (content_id) REFERENCES ns_content(id)
);


CREATE INDEX idx_ns_program_content_program_id ON ns_program_content (program_id);
CREATE INDEX idx_ns_program_content_content_id ON ns_program_content (content_id);
CREATE INDEX idx_ns_program_content_order ON ns_program_content (program_id, play_order);
CREATE UNIQUE INDEX idx_ns_program_content_pid ON ns_program_content(pid);

-- 播放列表表
CREATE TABLE ns_playlist (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT NOT NULL,
    
    playlist_name VARCHAR(100) NOT NULL,
    playlist_type VARCHAR(20) DEFAULT 'normal',  -- NORMAL, EMERGENCY, TEMPLATE
    
    -- 播放配置
    total_duration INTEGER,
    loop_enabled BOOLEAN DEFAULT TRUE,
    
    -- 优先级
    priority INTEGER DEFAULT 1,  -- 1-10，数字越大优先级越高
    
    -- 时间配置
    schedule_config JSON,
    
    -- 状态
    status VARCHAR(20) DEFAULT 'draft',  -- draft, published, archived
    
    description TEXT,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);


CREATE INDEX idx_ns_playlist_tenant_id ON ns_playlist (tenant_id);
CREATE INDEX idx_ns_playlist_status ON ns_playlist (status);
CREATE INDEX idx_ns_playlist_type ON ns_playlist (playlist_type);
CREATE INDEX idx_ns_playlist_priority ON ns_playlist (priority);
CREATE UNIQUE INDEX idx_ns_playlist_pid ON ns_playlist(pid);

-- 播放列表节目关联表
CREATE TABLE ns_playlist_program (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    playlist_id BIGINT NOT NULL,
    program_id BIGINT NOT NULL,
    
    -- 播放顺序
    play_order INTEGER NOT NULL,
    
    -- 播放配置
    repeat_count INTEGER DEFAULT 1,
    
    -- 时间配置
    start_time TIME,
    end_time TIME,
    weekdays VARCHAR(20),  -- 1,2,3,4,5,6,7 表示周一到周日
    
    -- 扩展配置
    config JSON,
    
    status VARCHAR(20) DEFAULT 'active',
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (playlist_id) REFERENCES ns_playlist(id),
    FOREIGN KEY (program_id) REFERENCES ns_program(id)
);


CREATE INDEX idx_ns_playlist_program_playlist_id ON ns_playlist_program (playlist_id);
CREATE INDEX idx_ns_playlist_program_program_id ON ns_playlist_program (program_id);
CREATE INDEX idx_ns_playlist_program_order ON ns_playlist_program (playlist_id, play_order);
CREATE UNIQUE INDEX idx_ns_playlist_program_pid ON ns_playlist_program(pid);

-- 设备播放列表关联表
CREATE TABLE ns_device_playlist (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    device_id BIGINT NOT NULL,
    playlist_id BIGINT NOT NULL,
    
    -- 发布配置
    publish_time TIMESTAMP,
    effective_time TIMESTAMP,
    expire_time TIMESTAMP,
    
    -- 状态
    status VARCHAR(20) DEFAULT 'pending',  -- pending, published, active, expired
    
    -- 优先级（数字越大优先级越高）
    priority INTEGER DEFAULT 0,
    
    -- 发布配置（JSON格式）
    publish_config JSON,
    
    -- 播放模式：NORMAL-正常播放，LOOP-循环播放，SCHEDULE-定时播放
    play_mode VARCHAR(20) DEFAULT 'normal',
    
    -- 循环次数（0表示无限循环）
    loop_count INTEGER DEFAULT 1,
    
    -- 音量（0-100）
    volume INTEGER DEFAULT 50,
    
    -- 播放统计
    play_count INTEGER DEFAULT 0,
    last_play_time TIMESTAMP,
    
    -- 租户ID
    tenant_id BIGINT NOT NULL,
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (device_id) REFERENCES ns_device(id),
    FOREIGN KEY (playlist_id) REFERENCES ns_playlist(id),
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);


CREATE INDEX idx_ns_device_playlist_device_id ON ns_device_playlist (device_id);
CREATE INDEX idx_ns_device_playlist_playlist_id ON ns_device_playlist (playlist_id);
CREATE INDEX idx_ns_device_playlist_status ON ns_device_playlist (status);
CREATE INDEX idx_ns_device_playlist_effective_time ON ns_device_playlist (effective_time);
CREATE INDEX idx_ns_device_playlist_tenant_id ON ns_device_playlist (tenant_id);
CREATE UNIQUE INDEX idx_ns_device_playlist_pid ON ns_device_playlist(pid);

-- 播放日志表
CREATE TABLE ns_play_log (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    device_id BIGINT NOT NULL,
    playlist_id BIGINT,
    program_id BIGINT,
    content_id BIGINT,
    
    -- 播放信息
    play_start_time TIMESTAMP,
    play_end_time TIMESTAMP,
    play_duration INTEGER,  -- 实际播放时长(秒)
    
    -- 播放状态
    play_status VARCHAR(20),  -- STARTED, completed, INTERRUPTED, ERROR
    error_message TEXT,
    
    -- 设备状态
    device_status JSON,
    
    FOREIGN KEY (device_id) REFERENCES ns_device(id),
    FOREIGN KEY (playlist_id) REFERENCES ns_playlist(id),
    FOREIGN KEY (program_id) REFERENCES ns_program(id),
    FOREIGN KEY (content_id) REFERENCES ns_content(id)
);


CREATE INDEX idx_ns_play_log_device_id ON ns_play_log (device_id);
CREATE INDEX idx_ns_play_log_created_at ON ns_play_log (created_at);
CREATE INDEX idx_ns_play_log_status ON ns_play_log (play_status);
CREATE UNIQUE INDEX idx_ns_play_log_pid ON ns_play_log(pid);

-- 设备分组表
CREATE TABLE ns_device_group (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    tenant_id BIGINT NOT NULL,
    
    group_name VARCHAR(100) NOT NULL,
    group_type VARCHAR(20) DEFAULT 'custom',  -- CUSTOM, STORE, FLOOR, ZONE
    
    description TEXT,
    
    status VARCHAR(20) DEFAULT 'active',
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (tenant_id) REFERENCES ns_tenant(id)
);


CREATE INDEX idx_ns_device_group_tenant_id ON ns_device_group (tenant_id);
CREATE INDEX idx_ns_device_group_type ON ns_device_group (group_type);
CREATE UNIQUE INDEX idx_ns_device_group_pid ON ns_device_group(pid);

-- 设备分组关联表
CREATE TABLE ns_device_group_member (
    id BIGINT PRIMARY KEY,
    pid VARCHAR(26) UNIQUE NOT NULL DEFAULT '',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    group_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    
    status VARCHAR(20) DEFAULT 'active',
    
    deleted_flag BOOLEAN DEFAULT FALSE,
    created_by BIGINT,
    updated_by BIGINT,
    
    FOREIGN KEY (group_id) REFERENCES ns_device_group(id),
    FOREIGN KEY (device_id) REFERENCES ns_device(id)
);


CREATE INDEX idx_ns_device_group_member_group_id ON ns_device_group_member (group_id);
CREATE INDEX idx_ns_device_group_member_device_id ON ns_device_group_member (device_id);
CREATE UNIQUE INDEX idx_ns_device_group_member_unique ON ns_device_group_member (group_id, device_id) ;
CREATE UNIQUE INDEX idx_ns_device_group_member_pid ON ns_device_group_member(pid);


-- 创建设备操作日志表
CREATE TABLE IF NOT EXISTS ns_device_operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pid VARCHAR(26) NOT NULL UNIQUE,
    device_id BIGINT NOT NULL,
    device_name VARCHAR(100),
    store_id BIGINT,
    store_name VARCHAR(100),
    operation_type VARCHAR(20) NOT NULL CHECK (operation_type IN ('bind', 'unbind', 'batch_bind', 'batch_unbind')),
    operation_result VARCHAR(10) NOT NULL CHECK (operation_result IN ('success', 'failed')),
    description TEXT,
    error_message TEXT,
    operator_id BIGINT,
    operator_name VARCHAR(50),
    tenant_id BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT,
    deleted_flag BOOLEAN DEFAULT FALSE
);

-- 创建索引
CREATE INDEX idx_device_operation_log_device_id ON ns_device_operation_log(device_id);
CREATE INDEX idx_device_operation_log_store_id ON ns_device_operation_log(store_id);
CREATE INDEX idx_device_operation_log_operator_id ON ns_device_operation_log(operator_id);
CREATE INDEX idx_device_operation_log_tenant_id ON ns_device_operation_log(tenant_id);
CREATE INDEX idx_device_operation_log_created_at ON ns_device_operation_log(created_at);
CREATE INDEX idx_device_operation_log_operation_type ON ns_device_operation_log(operation_type);
CREATE INDEX idx_device_operation_log_operation_result ON ns_device_operation_log(operation_result);

-- 添加注释
