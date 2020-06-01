package com.dtstack.engine.api.service;

import com.dtstack.engine.api.annotation.Param;
import com.dtstack.engine.api.domain.NodeMachine;

import java.util.List;

public interface NodeMachineService {
    @Deprecated
    public List<NodeMachine> listByAppType(@Param("appType") String appType);

    @Deprecated
    public NodeMachine getByAppTypeAndMachineType(@Param("appType") String appType, @Param("machineType") int machineType);
}
