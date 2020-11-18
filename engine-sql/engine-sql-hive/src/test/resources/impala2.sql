create table IF NOT EXISTS temp_ods_lsd_cardii_cur_cy
 AS
 select a.cardii id , a.cardii_idnum ,  a.cardii account ,
 a.cardii_account_name ,  a.cardii_core_account_no ,
 a.cardii_bind_card ,  a.cardii_mobile ,  a.cardii_node_no ,
 a.cardii_frozen_amount ,  a.cardii_version ,  a.cardii_create_time ,
 a.cardii_update_time ,  a.cardii_is_new_core_account_no ,
 a.cardii_status ,
 from_timestamp(to_timestamp(cast('20200820' AS STRING),'yyyyMMdd'),'yyyy-MM-dd') AS dw_eti_date,
 substr(from_timestamp(to_timestamp (cast ('20200820' AS STRING ), 'yyyyMMad'), 'yyyy-MM-dd'),1,7) AS du_eti_month
 from (
	SELECT * FROM ods_lsd_cardii      WHERE dw_eti_date = from_timestamp(adddate(to_timestamp (cast('20200820' AS STRING ), 'yyyyMMdd'), -1), 'yyyy-MM-dd'))
    ) a
 left join src_1sd_cardii b     on a.cardii_id = b.cardii_id     and b.pt = cast('20200820' as string)      where b.cardii_id is NULL       UNION ALL      select      cardii_id cardii_idnum  , cardii_account  , cardii_account_name , cardii_core_account_no  cardii_bind_card  , cardii_mobile ,cardii_node_no  ,frozen_amount ,cardii_version  ,cardii_create_time  , cardii_update_time  , cardii_is_new_core_account_no  , cardii_status   , from_timestamp(to_timestamp (cast('20200820' AS STRING ), 'yyyymdd' ), 'yyyy-MM-dd') AS dw_eti_date  , substr(from_timestamp(to_timestamp(cast('20200820' AS STRING ), 'yyyyMMdd"), 'yyyy-MM-dd),1,7) AS dw_eti_month  from src_lsd_cardii   where pt = cast('20200820' AS STRING )