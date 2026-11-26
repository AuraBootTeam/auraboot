CREATE TABLE ab_plugin_rest_publication (
    namespace varchar(128) NOT NULL,
    kind varchar(64) NOT NULL,
    public_id varchar(128) NOT NULL,
    tenant_id bigint NOT NULL,
    parent_kind varchar(64),
    parent_id varchar(128),
    document jsonb NOT NULL,
    CONSTRAINT pk_plugin_rest_publication PRIMARY KEY (namespace, kind, public_id),
    CONSTRAINT ck_plugin_rest_publication_parent CHECK ((parent_kind IS NULL) = (parent_id IS NULL)),
    CONSTRAINT ck_plugin_rest_publication_document CHECK (jsonb_typeof(document) = 'object')
);
