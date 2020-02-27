
-- 新增字段
alter table rdos_engine_job_cache add COLUMN is_failover tinyint(1) NOT NULL DEFAULT '0' COMMENT '0：不是，1：由故障恢复来的任务';
alter table rdos_engine_job_cache change `group_name` `job_resource` varchar(256)  DEFAULT '' COMMENT '计算引擎类型';
alter table rdos_engine_job_stop_record change `group_name` `job_resource` varchar(256)  DEFAULT '' COMMENT '计算引擎类型';



