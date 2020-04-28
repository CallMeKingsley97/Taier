CREATE TABLE `console_cluster` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `cluster_name` varchar(24) NOT NULL COMMENT '集群名称',
  `hadoop_version` varchar(24) NOT NULL COMMENT 'hadoop版本',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx` (`cluster_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `console_engine` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `cluster_id` int(11) NOT NULL COMMENT '集群id',
  `engine_name` varchar(24) NOT NULL COMMENT '引擎名称',
  `engine_type` tinyint(4) NOT NULL COMMENT '引擎类型',
  `total_node` int(11) NOT NULL COMMENT '节点数',
  `total_memory` int(11) NOT NULL COMMENT '总内存',
  `total_core` int(11) NOT NULL COMMENT '总核数',
  `sync_type` tinyint(1) NULL COMMENT '获取元数据组件类型',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `console_component` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `engine_id` int(11) NOT NULL COMMENT '引擎id',
  `component_name` varchar(24) NOT NULL COMMENT '组件名称',
  `component_type_code` tinyint(1) NOT NULL COMMENT '组件类型',
  `component_config` text NOT NULL COMMENT '组件配置',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `console_dtuic_tenant` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `dt_uic_tenant_id` int(11) NOT NULL COMMENT 'uic租户id',
  `tenant_name` varchar(256) NOT NULL COMMENT '用户名称',
  `tenant_desc` varchar(256) DEFAULT '' COMMENT '租户描述',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `console_engine_tenant` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `tenant_id` int(11) NOT NULL COMMENT '租户id',
  `engine_id` int(11) NOT NULL COMMENT '引擎id',
  `queue_id` int(11) NULL COMMENT '队列id',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `console_queue` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `engine_id` int(11) NOT NULL COMMENT '引擎id',
  `queue_name` varchar(24) NOT NULL COMMENT '队列名称',
  `capacity` varchar(24) NOT NULL COMMENT '最小容量',
  `max_capacity` varchar(24) NOT NULL COMMENT '最大容量',
  `queue_state` varchar(24) NOT NULL COMMENT '运行状态',
  `parent_queue_id` int(11) NOT NULL COMMENT '父队列id',
  `queue_path` varchar(256) NOT NULL COMMENT '队列路径',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `console_kerberos` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `cluster_id` int(11) NOT NULL COMMENT '集群id',
  `open_kerberos` tinyint(1) NOT NULL COMMENT '是否开启kerberos配置',
  `name` varchar(100) NOT NULL COMMENT 'kerberos文件名称',
  `remote_path` varchar(200) NOT NULL COMMENT 'sftp存储路径',
  `principal` varchar(50) NOT NULL COMMENT 'principal',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;


-- 账号表
CREATE TABLE `console_user`
(
    `id`                 int(11)      NOT NULL AUTO_INCREMENT,
    `dtuic_user_id`      int(11)      NOT NULL COMMENT 'dtuic userid',
    `user_name`          varchar(256) NOT NULL COMMENT '用户名称',
    `email`              varchar(256) NOT NULL COMMENT '用户手机号',
    `status`             tinyint(1)   NOT NULL DEFAULT '0' COMMENT '用户状态0：正常，1：禁用',
    `gmt_create`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
    `gmt_modified`       datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    `is_deleted`         tinyint(1)   NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
    `default_project_id` int(11)               DEFAULT NULL COMMENT '默认项目id',
    `phone_number`       varchar(256)          DEFAULT NULL COMMENT '用户手机号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `index_dtuic_user_id` (`dtuic_user_id`),
    KEY `index_user_name` (`user_name`(128))
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8;

CREATE TABLE `console_account`
(
    `id`             int(11)      NOT NULL AUTO_INCREMENT,
    `name`           varchar(24)  NOT NULL COMMENT '用户名',
    `password`       varchar(256) NOT NULL COMMENT '密码',
    `type`           tinyint(1)   NOT NULL COMMENT '账号类型',
    `gmt_create`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    `is_deleted`     tinyint(1)   NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
    `create_user_id` int(11)      NOT NULL,
    `modify_user_id` int(11)               DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8;

CREATE TABLE `console_account_tenant`
(
    `id`             int(11)    NOT NULL AUTO_INCREMENT,
    `account_id`     int(11)    NOT NULL COMMENT '数据库账号',
    `user_id`        int(11)    NOT NULL COMMENT '数栈绑定用户',
    `tenant_id`      int(11)    NOT NULL COMMENT '数栈绑定租户',
    `gmt_create`     datetime   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `gmt_modified`   datetime   NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
    `is_deleted`     tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
    `create_user_id` int(11)    NOT NULL,
    `modify_user_id` int(11)             DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 0
  DEFAULT CHARSET = utf8;