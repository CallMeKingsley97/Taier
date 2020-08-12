package com.dtstack.engine.api.service;

import com.dtstack.engine.api.pager.PageResult;
import com.dtstack.engine.api.vo.EngineTenantVO;
import com.dtstack.sdk.core.common.ApiResponse;
import com.dtstack.sdk.core.common.DtInsightServer;
import com.dtstack.sdk.core.feign.Param;
import com.dtstack.sdk.core.feign.RequestLine;

import java.util.List;

public interface TenantService extends DtInsightServer {

    @RequestLine("POST /node/tenant/pageQuery")
    ApiResponse<PageResult<List<EngineTenantVO>>> pageQuery(@Param("clusterId") Long clusterId,
                                                            @Param("engineType") Integer engineType,
                                                            @Param("tenantName") String tenantName,
                                                            @Param("pageSize") int pageSize,
                                                            @Param("currentPage") int currentPage);

    /**
     * 获取处于统一集群的全部tenant
     *
     * @param dtuicTenantId
     * @param engineType
     * @return
     */
    @RequestLine("POST /node/tenant/listEngineTenant")
    ApiResponse<List<EngineTenantVO>> listEngineTenant(@Param("dtuicTenantId") Long dtuicTenantId,
                                                       @Param("engineType") Integer engineType);

    @RequestLine("POST /node/tenant/listTenant")
    ApiResponse<List> listTenant(@Param("dtToken") String dtToken);

    @RequestLine("POST /node/tenant/bindingTenant")
    ApiResponse bindingTenant(@Param("tenantId") Long dtUicTenantId, @Param("clusterId") Long clusterId,
                              @Param("queueId") Long queueId, @Param("dtToken") String dtToken) throws Exception;


    @RequestLine("POST /node/tenant/bindingQueue")
    ApiResponse bindingQueue(@Param("queueId") Long queueId,
                             @Param("dtUicTenantId") Long dtUicTenantId);
}