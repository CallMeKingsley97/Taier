package com.dtstack.engine.api.domain;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * @author chener
 * @Classname LineageTableTable
 * @Description 存储纯粹的血缘关系
 * @Date 2020/10/22 20:15
 * @Created chener@dtstack.com
 */
@ApiModel
public class LineageTableTable extends TenantEntity {

    @ApiModelProperty(notes = "输入表id")
    private Integer inputTableId;

    @ApiModelProperty(notes = "输出表id")
    private Integer resultTableId;

    @ApiModelProperty(notes = "表级血缘关系定位码")
    private String tableLineageKey;

    @ApiModelProperty(notes = "血缘来源：0-sql解析；1-手动维护；2-json解析")
    private Integer lineageSource;

    @ApiModelProperty(notes = "血缘批次唯一码")
    private String uniqueKey;

    public Integer getInputTableId() {
        return inputTableId;
    }

    public void setInputTableId(Integer inputTableId) {
        this.inputTableId = inputTableId;
    }

    public Integer getResultTableId() {
        return resultTableId;
    }

    public void setResultTableId(Integer resultTableId) {
        this.resultTableId = resultTableId;
    }

    public String getTableLineageKey() {
        return tableLineageKey;
    }

    public void setTableLineageKey(String tableLineageKey) {
        this.tableLineageKey = tableLineageKey;
    }

    public Integer getLineageSource() {
        return lineageSource;
    }

    public void setLineageSource(Integer lineageSource) {
        this.lineageSource = lineageSource;
    }

    public String getUniqueKey() {
        return uniqueKey;
    }

    public void setUniqueKey(String uniqueKey) {
        this.uniqueKey = uniqueKey;
    }
}
