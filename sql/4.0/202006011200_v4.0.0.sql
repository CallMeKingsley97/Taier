ALTER table console_component ADD COLUMN hadoop_version VARCHAR(25) DEFAULT ''  COMMENT '组件hadoop版本';
ALTER table console_component ADD COLUMN upload_file_name VARCHAR(50) DEFAULT ''  COMMENT '上传文件zip名称';
ALTER table console_component ADD COLUMN component_template text COMMENT '前端展示模版json';
ALTER table console_component ADD COLUMN kerberos_file_name VARCHAR(50) DEFAULT ''  COMMENT '上传kerberos文件zip名称';
ALTER table console_kerberos ADD COLUMN component_type int(11) COMMENT '组件类型';
ALTER table console_kerberos ADD COLUMN krb_name VARCHAR(26) COMMENT 'krb名称';

-- 删除default集群 配置
update console_component set is_deleted = 1 WHERE engine_id IN(
    (SELECT ce.id from console_cluster cc
                           LEFT JOIN console_engine ce
                                     on ce.cluster_id = cc.id
     WHERE cc.id = -1))