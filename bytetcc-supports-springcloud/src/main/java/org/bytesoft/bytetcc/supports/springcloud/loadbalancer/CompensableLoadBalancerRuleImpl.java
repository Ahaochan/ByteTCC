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
package org.bytesoft.bytetcc.supports.springcloud.loadbalancer;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytetcc.supports.springcloud.SpringCloudBeanRegistry;
import org.bytesoft.bytetcc.supports.springcloud.rule.CompensableRule;
import org.bytesoft.bytetcc.supports.springcloud.rule.CompensableRuleImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.AbstractLoadBalancerRule;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.IRule;
import com.netflix.loadbalancer.Server;

// ByteTcc重写了Ribbon的路由规则
public class CompensableLoadBalancerRuleImpl extends AbstractLoadBalancerRule implements IRule {
	static final String CONSTANT_RULE_KEY = "org.bytesoft.bytetcc.NFCompensableRuleClassName";
	static Logger logger = LoggerFactory.getLogger(CompensableLoadBalancerRuleImpl.class);

	static Class<?> compensableRuleClass;

	private IClientConfig clientConfig;

	public Server choose(Object key) {
		SpringCloudBeanRegistry registry = SpringCloudBeanRegistry.getInstance();
		CompensableLoadBalancerInterceptor interceptor = registry.getLoadBalancerInterceptor();

		// 1. 初始化分布式事务的路由规则处理类, 没有就默认用CompensableRuleImpl
		if (compensableRuleClass == null) {
			// 从环境变量中读取分布式事务的路由处理类
			Environment environment = registry.getEnvironment();
			String clazzName = environment.getProperty(CONSTANT_RULE_KEY);
			if (StringUtils.isNotBlank(clazzName)) {
				try {
					// 用当前线程的类加载器进行加载
					ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
					compensableRuleClass = classLoader.loadClass(clazzName);
				} catch (Exception ex) {
					logger.error("Error occurred while loading class {}.", clazzName, ex);
					compensableRuleClass = CompensableRuleImpl.class;
				}
			} else {
				// 没有就默认用CompensableRuleImpl
				compensableRuleClass = CompensableRuleImpl.class;
			}
		}

		// 2. new 一个CompensableRule对象出来, 并初始化
		CompensableRule compensableRule = null;
		if (CompensableRuleImpl.class.equals(compensableRuleClass)) {
			compensableRule = new CompensableRuleImpl();
		} else {
			try {
				compensableRule = (CompensableRule) compensableRuleClass.newInstance();
			} catch (Exception ex) {
				logger.error("Can not create an instance of class {}.", compensableRuleClass.getName(), ex);
				compensableRule = new CompensableRuleImpl();
			}
		}
		compensableRule.initWithNiwsConfig(this.clientConfig);
		compensableRule.setLoadBalancer(this.getLoadBalancer());

		if (interceptor == null) {
			// 如果没有拦截器, 就随机取一个实例
			return compensableRule.chooseServer(key); // return this.chooseServer(key);
		} // end-if (interceptor == null)

		ILoadBalancer loadBalancer = this.getLoadBalancer();
		List<Server> servers = loadBalancer.getAllServers();

		Server server = null;
		try {
			// 拦截器过滤筛选可用的服务实例列表
			List<Server> serverList = interceptor.beforeCompletion(servers);

			// 从过滤完的服务实例列表里取出一个实例
			server = compensableRule.chooseServer(key, serverList); // this.chooseServer(key, serverList);
		} finally {
			// 完事后做一个后置处理
			// 这里抛出异常, 可能会导致LoadBalancerContext报错Load balancer does not have available server for client
			interceptor.afterCompletion(server);
		}

		// 将选中的服务实例返回, 用来进行http通信
		return server;
	}

	public void initWithNiwsConfig(IClientConfig clientConfig) {
		this.clientConfig = clientConfig;
	}

	public IClientConfig getClientConfig() {
		return clientConfig;
	}

}
