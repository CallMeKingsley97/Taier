package com.dtstack.rdos.engine.db.dataobject;

/**
 * 
 * Reason: TODO ADD REASON(可选)
 * Date: 2016年02月21日 下午1:16:37
 * Company: www.dtstack.com
 * @author sishu.yss
 *
 */
public class RdosNodeMachine extends DataObject{
	
	/**
	 * node 主机ip
	 */
	private String ip;
	
	/**
	 * node 端口
	 */
	private Long port;
	
	/**
	 * 0 master 1 slave
	 */
	private Integer machineType;

	public RdosNodeMachine(String ip2, Long port2, Integer machineType2) {
		// TODO Auto-generated constructor stub
		this.ip=ip2;
		this.port = port2;
		this.machineType = machineType2;
	}
	
	public RdosNodeMachine(){
		
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public Long getPort() {
		return port;
	}

	public void setPort(Long port) {
		this.port = port;
	}

	public Integer getMachineType() {
		return machineType;
	}

	public void setMachineType(Integer machineType) {
		this.machineType = machineType;
	}


}
