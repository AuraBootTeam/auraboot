CREATE DATABASE IF NOT EXISTS `xxl_job` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `xxl_job`;

SET NAMES utf8mb4;

CREATE TABLE `xxl_job_group`
(
    `id`           int          NOT NULL AUTO_INCREMENT,
    `app_name`     varchar(64)  NOT NULL,
    `title`        varchar(64)  NOT NULL,
    `address_type` tinyint      NOT NULL DEFAULT '0',
    `address_list` text                  DEFAULT NULL,
    `update_time`  datetime              DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_registry`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT,
    `registry_group` varchar(50)  NOT NULL,
    `registry_key`   varchar(255) NOT NULL,
    `registry_value` varchar(255) NOT NULL,
    `update_time`    datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_g_k_v` (`registry_group`, `registry_key`, `registry_value`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_info`
(
    `id`                        int          NOT NULL AUTO_INCREMENT,
    `job_group`                 int          NOT NULL,
    `job_desc`                  varchar(255) NOT NULL,
    `add_time`                  datetime              DEFAULT NULL,
    `update_time`               datetime              DEFAULT NULL,
    `author`                    varchar(64)           DEFAULT NULL,
    `alarm_email`               varchar(255)          DEFAULT NULL,
    `schedule_type`             varchar(50)  NOT NULL DEFAULT 'NONE',
    `schedule_conf`             varchar(128)          DEFAULT NULL,
    `misfire_strategy`          varchar(50)  NOT NULL DEFAULT 'DO_NOTHING',
    `executor_route_strategy`   varchar(50)           DEFAULT NULL,
    `executor_handler`          varchar(255)          DEFAULT NULL,
    `executor_param`            text                  DEFAULT NULL,
    `executor_block_strategy`   varchar(50)           DEFAULT NULL,
    `executor_timeout`          int          NOT NULL DEFAULT '0',
    `executor_fail_retry_count` int          NOT NULL DEFAULT '0',
    `glue_type`                 varchar(50)  NOT NULL,
    `glue_source`               mediumtext            DEFAULT NULL,
    `glue_remark`               varchar(128)          DEFAULT NULL,
    `glue_updatetime`           datetime              DEFAULT NULL,
    `child_jobid`               varchar(255)          DEFAULT NULL,
    `trigger_status`            tinyint      NOT NULL DEFAULT '0',
    `trigger_last_time`         bigint       NOT NULL DEFAULT '0',
    `trigger_next_time`         bigint       NOT NULL DEFAULT '0',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_logglue`
(
    `id`          int          NOT NULL AUTO_INCREMENT,
    `job_id`      int          NOT NULL,
    `glue_type`   varchar(50)           DEFAULT NULL,
    `glue_source` mediumtext            DEFAULT NULL,
    `glue_remark` varchar(128) NOT NULL,
    `add_time`    datetime              DEFAULT NULL,
    `update_time` datetime              DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_log`
(
    `id`                        bigint NOT NULL AUTO_INCREMENT,
    `job_group`                 int    NOT NULL,
    `job_id`                    int    NOT NULL,
    `executor_address`          varchar(255) DEFAULT NULL,
    `executor_handler`          varchar(255) DEFAULT NULL,
    `executor_param`            text         DEFAULT NULL,
    `executor_sharding_param`   varchar(20)  DEFAULT NULL,
    `executor_fail_retry_count` int    NOT NULL DEFAULT '0',
    `trigger_time`              datetime     DEFAULT NULL,
    `trigger_code`              int    NOT NULL,
    `trigger_msg`               text         DEFAULT NULL,
    `handle_time`               datetime     DEFAULT NULL,
    `handle_code`               int    NOT NULL,
    `handle_msg`                text         DEFAULT NULL,
    `alarm_status`              tinyint NOT NULL DEFAULT '0',
    PRIMARY KEY (`id`),
    KEY `I_trigger_time` (`trigger_time`),
    KEY `I_handle_code` (`handle_code`),
    KEY `I_jobgroup` (`job_group`),
    KEY `I_jobid` (`job_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_log_report`
(
    `id`            int NOT NULL AUTO_INCREMENT,
    `trigger_day`   datetime DEFAULT NULL,
    `running_count` int NOT NULL DEFAULT '0',
    `suc_count`     int NOT NULL DEFAULT '0',
    `fail_count`    int NOT NULL DEFAULT '0',
    `update_time`   datetime DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_trigger_day` (`trigger_day`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_lock`
(
    `lock_name` varchar(50) NOT NULL,
    PRIMARY KEY (`lock_name`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

CREATE TABLE `xxl_job_user`
(
    `id`         int          NOT NULL AUTO_INCREMENT,
    `username`   varchar(50)  NOT NULL,
    `password`   varchar(100) NOT NULL,
    `token`      varchar(100)          DEFAULT NULL,
    `role`       tinyint      NOT NULL,
    `permission` varchar(255)          DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `i_username` (`username`) USING BTREE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

INSERT INTO `xxl_job_user` (`id`, `username`, `password`, `role`, `permission`)
VALUES (1, 'admin', '8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92', 1, NULL);

INSERT INTO `xxl_job_lock` (`lock_name`)
VALUES ('schedule_lock');
