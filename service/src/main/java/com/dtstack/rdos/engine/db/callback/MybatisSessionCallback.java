package com.dtstack.rdos.engine.db.callback;

import org.apache.ibatis.session.SqlSession;

/**
 * 
 * Reason: TODO ADD REASON(可选)
 * Date: 2017年03月03日 下午1:25:18
 * Company: www.dtstack.com
 * @author sishu.yss
 *
 */
public interface MybatisSessionCallback<T> {

	T execute(SqlSession sqlSession) throws Exception;
}
