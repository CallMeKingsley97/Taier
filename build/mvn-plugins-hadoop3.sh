#!/usr/bin/env bash

hadoopversion=$1
if [ ! -n "$hadoopversion" ]
then
    hadoopversion=3.0.0
fi
echo "Dependency ${hadoopversion} Building..."

mvn clean package -DskipTests -Dhadoop.version=${hadoopversion} -Dhadoop3.version=${hadoopversion} -pl \
engine-worker/engine-plugins/dummy,\
engine-worker/engine-plugins/hadoop/yarn3-hdfs3-hadoop3,\
engine-worker/engine-plugins/dtscript/yarn3-hdfs3-dtscript/dtscript-client,\
engine-worker/engine-plugins/learning/yarn3-hdfs3-learning/learning-client,\
engine-worker/engine-plugins/flink/common,\
engine-worker/engine-plugins/flink/yarn3-hdfs3-flink180,\
engine-worker/engine-plugins/flink/yarn3-hdfs3-flink110,\
engine-worker/engine-plugins/spark/yarn3-hdfs3-spark210/spark-yarn-client,\
engine-worker/engine-plugins/spark/yarn3-hdfs3-spark210/spark-sql-proxy,\
engine-worker/engine-plugins/spark/yarn3-hdfs3-spark240/spark-yarn-client,\
engine-worker/engine-plugins/spark/yarn3-hdfs3-spark240/spark-sql-proxy,\
engine-worker/engine-plugins/stores/nfs,\
engine-worker/engine-plugins/stores/hdfs3,\
engine-worker/engine-plugins/schedules/yarn3,\
engine-worker/engine-plugins/kylin,\
engine-worker/engine-plugins/odps,\
engine-worker/engine-plugins/rdbs/mysql,\
engine-worker/engine-plugins/rdbs/oracle,\
engine-worker/engine-plugins/rdbs/sqlserver,\
engine-worker/engine-plugins/rdbs/hive,\
engine-worker/engine-plugins/rdbs/hive2,\
engine-worker/engine-plugins/rdbs/hive-2.1.1-cdh6.1.1,\
engine-worker/engine-plugins/rdbs/postgresql,\
engine-worker/engine-plugins/rdbs/impala,\
engine-worker/engine-plugins/rdbs/tidb,\
engine-worker/engine-plugins/rdbs/greenplum,\
engine-worker/engine-plugins/rdbs/presto,\
engine-entrance \
-am