/**
 * Copyright 2014-2017 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc.supports.springcloud.feign;

import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.common.utils.SerializeUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.CompensableTransaction;
import org.bytesoft.compensable.TransactionContext;
import org.bytesoft.compensable.aware.CompensableEndpointAware;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

// Feign拦截器
public class CompensableFeignInterceptor
		implements feign.RequestInterceptor, CompensableEndpointAware, ApplicationContextAware {
	static final String HEADER_TRANCACTION_KEY = "X-BYTETCC-TRANSACTION"; // org.bytesoft.bytetcc.transaction
	static final String HEADER_PROPAGATION_KEY = "X-BYTETCC-PROPAGATION"; // org.bytesoft.bytetcc.propagation

	private String identifier;
	private ApplicationContext applicationContext;

	public void apply(feign.RequestTemplate template) {
		final SpringCloudBeanRegistry beanRegistry = SpringCloudBeanRegistry.getInstance();
		CompensableBeanFactory beanFactory = beanRegistry.getBeanFactory();
		CompensableManager compensableManager = beanFactory.getCompensableManager();
		CompensableTransaction compensable = compensableManager.getCompensableTransactionQuietly();
		if (compensable == null) {
			// 如果没有开启分布式事务, 就直接返回, 不处理
			return;
		}

		try {
			// 将TCC分布式事务上下文序列化, 转为base64编码
			TransactionContext transactionContext = compensable.getTransactionContext();
			byte[] byteArray = SerializeUtils.serializeObject(transactionContext);

			String transactionText = Base64.getEncoder().encodeToString(byteArray);

			// 放到请求头里
			Map<String, Collection<String>> headers = template.headers();
			if (headers.containsKey(HEADER_TRANCACTION_KEY) == false) {
				template.header(HEADER_TRANCACTION_KEY, transactionText);
			}

			if (headers.containsKey(HEADER_PROPAGATION_KEY) == false) {
				template.header(HEADER_PROPAGATION_KEY, identifier);
			}

		} catch (IOException ex) {
			throw new RuntimeException("Error occurred while preparing the transaction context!", ex);
		}
	}

	public String getEndpoint() {
		return this.identifier;
	}

	public void setEndpoint(String identifier) {
		this.identifier = identifier;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
