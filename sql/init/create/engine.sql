CREATE TABLE `rdos_plugin_info` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `plugin_key` varchar(255) NOT NULL COMMENT '插件配置信息md5值',
  `plugin_info` text NOT NULL COMMENT '插件信息',
  `type` tinyint(2) NOT NULL COMMENT '类型 0:默认插件, 1:动态插件(暂时数据库只存动态插件)',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `index_plugin_id` (`plugin_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `rdos_engine_batch_job` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '任务状态 UNSUBMIT(0),CREATED(1),SCHEDULED(2),DEPLOYING(3),RUNNING(4),FINISHED(5),CANCELING(6),CANCELED(7),FAILED(8)',
  `job_id` varchar(256) NOT NULL COMMENT '离线任务id',
  `engine_job_id` varchar(256)  COMMENT '离线任务计算引擎id',
  `exec_start_time` datetime  COMMENT '执行开始时间',
  `exec_end_time` datetime  COMMENT '执行结束时间',
  `exec_time` int(11) DEFAULT '0' COMMENT '执行时间',
  `log_info` mediumtext COMMENT '错误信息',
  `engine_log` longtext COMMENT '引擎错误信息',
  `plugin_info_id` int(11) COMMENT '插件信息',
  `source_type` tinyint(2) COMMENT '任务来源',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`),
  KEY `index_engine_job_id` (`engine_job_id`(128)),
  unique KEY `index_job_id` (`job_id`(128),`is_deleted`),
  KEY `index_status` (`status`)
) ENGINE=InnoDB  DEFAULT CHARSET=utf8;

CREATE TABLE `rdos_engine_stream_job` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `status` tinyint(1) NOT NULL DEFAULT '0' COMMENT '任务状态 UNSUBMIT(0),CREATED(1),SCHEDULED(2),DEPLOYING(3),RUNNING(4),FINISHED(5),CANCELING(6),CANCELED(7),FAILED(8)',
  `task_id` varchar(256) NOT NULL COMMENT '离线任务id',
  `engine_task_id` varchar(256)  COMMENT '离线任务计算引擎id',
  `exec_start_time` datetime  DEFAULT CURRENT_TIMESTAMP COMMENT '执行开始时间',
  `exec_end_time` datetime  DEFAULT CURRENT_TIMESTAMP COMMENT '执行结束时间',
  `exec_time` int(11) DEFAULT '0' COMMENT '执行时间',
  `log_info` mediumtext COMMENT '错误信息',
  `engine_log` longtext COMMENT '引擎错误信息',
  `plugin_info_id` int(11) COMMENT '插件信息',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`),
  KEY `index_engine_task_id` (`engine_task_id`(128)),
  unique KEY `index_task_id` (`task_id`(128),`is_deleted`),
  KEY `index_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=58 DEFAULT CHARSET=utf8;

create table `rdos_stream_task_checkpoint`(
	`id` int(11) not null AUTO_INCREMENT,
	`task_id` varchar(64) not null COMMENT '任务id',
	`task_engine_id` varchar(64) not null COMMENT '任务对于的引擎id',
	`checkpoint` longtext,
	`trigger_start` TIMESTAMP,
  `trigger_end` TIMESTAMP,
	`gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
	PRIMARY KEY (`id`)
)ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `rdos_engine_job_cache` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `job_id` varchar(256) NOT NULL COMMENT '任务id',
  `engine_type` varchar(256) NOT NULL COMMENT '任务的执行引擎类型',
  `compute_type` tinyint(2) NOT NULL COMMENT '计算类型stream/batch',
  `stage` tinyint(2) NOT NULL COMMENT '处于master等待队列：1 还是exe等待队列 2',
  `job_info` longtext NOT NULL COMMENT 'job信息',
  `node_address` varchar(256) DEFAULT NULL COMMENT '节点地址',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8;

CREATE TABLE `rdos_plugin_job_info` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `job_id` varchar(255) NOT NULL COMMENT '任务id',
  `job_info` LONGTEXT NOT NULL COMMENT '任务信息',
  `log_info` text COMMENT '任务信息',
  `status` tinyint(2) NOT NULL COMMENT '任务状态',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `index_job_id` (`job_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `rdos_engine_unique_sign` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `unique_sign` varchar(255) NOT NULL COMMENT '唯一标识',
  `gmt_create` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '新增时间',
  `gmt_modified` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '修改时间',
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0正常 1逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `index_unique_sign` (`unique_sign`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

