#!/usr/bin/env bash
echo 'Dependency Hadoop-2.7.3&Yarn Building...'

hadoopversion=$1
if [ ! -n "$hadoopversion" ]
then
    hadoopversion=2.7.3
fi
echo "Dependency ${hadoopversion} Building..."

mvn -T 1C clean package -DskipTests -Dhadoop.version=${hadoopversion} -pl \
engine-worker/engine-plugins/dummy,\
engine-worker/engine-plugins/common,\
engine-entrance \
-am

