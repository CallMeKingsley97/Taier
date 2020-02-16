-- 迁移ide的数据到task数据库中
insert into rdos_batch_task_shade( tenant_id, project_id, dtuic_tenant_id, app_type, node_pid, name, task_type, engine_type, compute_type, sql_text, task_params, task_id, schedule_conf, period_type, schedule_status, project_schedule_status, submit_status, gmt_create, gmt_modified, modify_user_id, create_user_id, owner_user_id, version_id, is_deleted, task_desc, main_class, exe_args, flow_id, is_publish_to_produce, extra_info, is_expire)
select
       ts.tenant_id,
       ts.project_id,
       tr.dtuic_tenant_id,
       1,
       ts.node_pid,
       ts.name,
       ts.task_type,
       ts.engine_type,
       ts.compute_type,
       ts.sql_text,
       ts.task_params,
       ts.id,
       ts.schedule_conf,
       ts.period_type,
       ts.schedule_status,
       rp.schedule_status,
       ts.submit_status,
       ts.gmt_create,
       ts.gmt_modified,
       ts.modify_user_id,
       ts.create_user_id,
       ts.owner_user_id,
       ts.version,
       ts.is_deleted,
       ts.task_desc,
       ts.main_class,
       ts.exe_args,
       ts.flow_id,
       ts.is_publish_to_produce,
       '',
       0
from ide.rdos_batch_task_shade ts
         left join ide.rdos_tenant tr on ts.tenant_id = tr.id
left join ide.rdos_project rp on ts.project_id = rp.id;



insert into rdos_batch_task_task_shade (tenant_id, project_id, dtuic_tenant_id, app_type, task_id, parent_task_id,
                                        gmt_create, gmt_modified, is_deleted)
select ts.tenant_id,
       ts.project_id,
       rt.dtuic_tenant_id,
       1,
       ts.task_id,
       ts.parent_task_id,
       ts.gmt_create,
       ts.gmt_modified,
       ts.is_deleted
from ide.rdos_batch_task_task_shade ts
         left join ide.rdos_tenant rt
                   on ts.tenant_id = rt.id;


insert into rdos_batch_job_job (tenant_id, project_id, dtuic_tenant_id, app_type, job_key, parent_job_key,
                                     gmt_create, gmt_modified, is_deleted)
select bj.tenant_id,
       bj.project_id,
       (select dtuic_tenant_id from ide.rdos_tenant where ide.rdos_tenant.id = bj.tenant_id),
       1,
       bj.job_key,
       jj.parent_job_key,
       jj.gmt_create,
       jj.gmt_modified,
       jj.is_deleted

from ide.rdos_batch_job_job jj
         left join ide.rdos_batch_job bj on bj.job_key = jj.job_key;


insert into rdos_batch_fill_data_job (tenant_id, project_id, dtuic_tenant_id, app_type, job_name, run_day,
                                                 from_day, to_day, gmt_create, gmt_modified, create_user_id, is_deleted)
select fdj.tenant_id,
       fdj.project_id,
       (select dtuic_tenant_id from ide.rdos_tenant where id = fdj.tenant_id),
       1,
       fdj.job_name,
       fdj.run_day,
       fdj.from_day,
       fdj.to_day,
       fdj.gmt_create,
       fdj.gmt_modified,
       fdj.create_user_id,
       fdj.is_deleted
from ide.rdos_batch_fill_data_job fdj;



insert into rdos_batch_job_alarm (tenant_id, project_id, dtuic_tenant_id, app_type, job_id, task_id,
                                             task_status, gmt_create, gmt_modified, is_deleted)
select bj.tenant_id
     , bj.project_id
     , (select dtuic_tenant_id from ide.rdos_tenant where id = bj.tenant_id) as dtuic_tenant_id
     , 1 as app_type
     , bj.id as job_id
     , bj.task_id
     , bja.task_status
     , bja.gmt_create
     , bja.gmt_modified
     , bja.is_deleted
from ide.rdos_batch_job_alarm bja
         left join ide.rdos_batch_job bj on bja.job_id = bj.id;



insert into rdos_batch_alarm (id, tenant_id, project_id, dtuic_tenant_id, app_type, name, task_id, my_trigger,
                                         uncomplete_time, status, create_user_id, gmt_create, gmt_modified, is_deleted,
                                         sender_type, is_task_holder, receivers)
select ba.id,
       ba.tenant_id,
       ba.project_id,
       (select dtuic_tenant_id from ide.rdos_tenant where id = ba.tenant_id),
       1,
       ba.name,
       ba.task_id,
       ba.my_trigger,
       ba.uncomplete_time,
       ba.status,
       ba.create_user_id,
       ba.gmt_create,
       ba.gmt_modified,
       ba.is_deleted,
       ba.sender_type,
       ba.is_task_holder,
       ''
from ide.rdos_batch_alarm ba;


insert into rdos_batch_alarm_record (id, tenant_id, project_id, alarm_id, cyc_time, alarm_content, trigger_type,
                                                gmt_create, gmt_modified, is_deleted, dtuic_tenant_id)

select ar.id,
       ar.tenant_id,
       ar.project_id,
       ar.alarm_id,
       ar.cyc_time,
       ar.alarm_content,
       ar.trigger_type,
       ar.gmt_create,
       ar.gmt_modified,
       ar.is_deleted,
       (select dtuic_tenant_id from ide.rdos_tenant where id = ar.tenant_id)
from ide.rdos_batch_alarm_record ar;


insert into rdos_notify (id,tenant_id, project_id, dtuic_tenant_id, app_type, biz_type, relation_id, name,
                              trigger_type, webhook, uncomplete_time, send_way, start_time, end_time, status,
                              create_user_id, gmt_create, gmt_modified, is_deleted)
select id,
       tenant_id,
       project_id,
       (select dtuic_tenant_id from ide.rdos_tenant where id = rn.tenant_id),
       1,
       biz_type,
       relation_id,
       name,
       trigger_type,
       webhook,
       uncomplete_time,
       send_way,
       start_time,
       end_time,
       status,
       create_user_id,
       gmt_create,
       gmt_modified,
       is_deleted
from ide.rdos_notify rn;


insert into rdos_notify_record (id,tenant_id, project_id, dtuic_tenant_id, app_type, notify_id, content_id, cyc_time,
                                     status, gmt_create, gmt_modified, is_deleted)
select id,
       tenant_id,
       project_id,
       (select dtuic_tenant_id from ide.rdos_tenant rt where rt.id = rnr.tenant_id),
       1,
       notify_id,
       content_id,
       cyc_time,
       status,
       gmt_create,
       gmt_modified,
       is_deleted
from ide.rdos_notify_record rnr;



insert into rdos_notify_user(tenant_id, project_id, dtuic_tenant_id, app_type, notify_id, user_id, gmt_create,
                                  gmt_modified, is_deleted)
select tenant_id,
       project_id,
       (select dtuic_tenant_id from ide.rdos_tenant where id = rnu.tenant_id),
       1,
       notify_id,
       user_id,
       gmt_create,
       gmt_modified,
       is_deleted
from ide.rdos_notify_user rnu;


insert into rdos_notify_alarm (id,tenant_id, project_id, dtuic_tenant_id, app_type, biz_type, notify_id, alarm_id,
                                    gmt_create, gmt_modified, is_deleted)
select id,
       tenant_id,
       project_id,
       (select dtuic_tenant_id from ide.rdos_tenant where id = rna.tenant_id),
       1,
       biz_type,
       notify_id,
       alarm_id,
       gmt_create,
       gmt_modified,
       is_deleted
from ide.rdos_notify_alarm rna;

-- 插入之后 在更新
insert into rdos_batch_job(tenant_id, project_id, dtuic_tenant_id, app_type, job_id, job_key, job_name,
                                      task_id,
                                      gmt_create, gmt_modified, create_user_id, is_deleted, type, is_restart,
                                      business_date,
                                      cyc_time, dependency_type, flow_job_id, period_type, status, task_type, fill_id,
                                      exec_start_time, exec_end_time, exec_time, submit_time, retry_num, node_address,
                                      version_id, log_info, next_cyc_time, max_retry_num)

select tenant_id,
       project_id,
       -1,
       1,
       job_id,
       job_key,
       job_name,
       task_id,
       gmt_create,
       gmt_modified,
       create_user_id,
       is_deleted,
       type,
       is_restart,
       business_date,
       cyc_time,
       dependency_type,
       flow_job_id,
       period_type,
       -1,
       -1,
       -1,
       null,
       null,
       null,
       null,
       0,
       '',
       0,
       '',
       '',
       0
from ide.rdos_batch_job;

update rdos_batch_job rbj left join ide.rdos_engine_batch_job rebj on rbj.job_id = rebj.job_id
set rbj.status          = IFNULL(rebj.status, 0),
    rbj.exec_start_time = rebj.exec_start_time,
    rbj.exec_end_time   = rebj.exec_end_time,
    rbj.exec_time       = rebj.exec_time,
    rbj.retry_num       = IFNULL(rebj.retry_num, 0),
    rbj.version_id      = rebj.version_id
where rbj.status = -1;

update rdos_batch_job rbj left join ide.rdos_batch_task bt on rbj.task_id = bt.id
set rbj.task_type = bt.task_type where bt.task_type is not null;


create index rdos_batch_fill_data_relation_job_id_index
    on ide.rdos_batch_fill_data_relation (job_id);

update rdos_batch_job rbj
set fill_id = (select fill_id
               from ide.rdos_batch_fill_data_relation fdr
               where rbj.id = fdr.job_id)
where type = 1;

update rdos_batch_job rbj
set dtuic_tenant_id = (select dtuic_tenant_id from ide.rdos_tenant where ide.rdos_tenant.id = rbj.tenant_id)
where dtuic_tenant_id = -1;