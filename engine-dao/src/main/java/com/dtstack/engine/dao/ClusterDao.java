package com.dtstack.engine.dao;

import com.dtstack.dtcenter.common.pager.PageQuery;
import com.dtstack.engine.domain.Cluster;
import com.dtstack.engine.dto.ClusterDTO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ClusterDao {

    Integer generalCount(@Param("model") ClusterDTO clusterDTO);

    List<Cluster> generalQuery(PageQuery<ClusterDTO> pageQuery);

    Integer insert(Cluster cluster);

    Integer insertWithId(Cluster cluster);

    Cluster getByClusterName(@Param("clusterName") String clusterName);

    Cluster getOne(@Param("id") Long clusterId);

    List<Cluster> listAll();

    Integer updateHadoopVersion(@Param("id") Long clusterId, @Param("hadoopVersion") String hadoopVersion);
}
