ALTER TABLE ab_plugin_rest_publication
    ADD COLUMN actor_pid varchar(128),
    ADD COLUMN command_code varchar(128),
    ADD COLUMN command_input jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_plugin_rest_publication_command CHECK ((actor_pid IS NULL) = (command_code IS NULL)),
    ADD CONSTRAINT ck_plugin_rest_publication_command_input CHECK (jsonb_typeof(command_input) = 'object');
