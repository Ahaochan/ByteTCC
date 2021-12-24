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
package org.bytesoft.bytetcc.supports.springcloud;

import java.lang.reflect.Proxy;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.internal.RemoteCoordinatorRegistry;
import org.bytesoft.bytetcc.TransactionBeanFactoryImpl;
import org.bytesoft.bytetcc.supports.springcloud.loadbalancer.CompensableLoadBalancerInterceptor;
import org.bytesoft.common.utils.CommonUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.remote.RemoteAddr;
import org.bytesoft.transaction.remote.RemoteCoordinator;
import org.bytesoft.transaction.remote.RemoteNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestTemplate;

public final class SpringCloudBeanRegistry implements CompensableBeanFactoryAware, EnvironmentAware {
	static final Logger logger = LoggerFactory.getLogger(SpringCloudBeanRegistry.class);
	private static final SpringCloudBeanRegistry instance = new SpringCloudBeanRegistry();

	private CompensableBeanFactory beanFactory = TransactionBeanFactoryImpl.getInstance();
	private RestTemplate restTemplate;
	private ThreadLocal<CompensableLoadBalancerInterceptor> interceptors = new ThreadLocal<CompensableLoadBalancerInterceptor>();
	private Environment environment;
	private transient boolean statefully;

	private SpringCloudBeanRegistry() {
		if (instance != null) {
			throw new IllegalStateException();
		}
	}

	public static SpringCloudBeanRegistry getInstance() {
		return instance;
	}

	public RemoteCoordinator getConsumeCoordinator(String identifier) {
		if (this.statefully) {
			return this.getConsumeCoordinatorStatefully(identifier);
		} else {
			return this.getConsumeCoordinatorStateless(identifier);
		}
	}

	public RemoteCoordinator getConsumeCoordinatorStatefully(String identifier) {
		RemoteCoordinatorRegistry registry = RemoteCoordinatorRegistry.getInstance();
		if (StringUtils.isBlank(identifier)) {
			return null;
		}

		RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(identifier);
		RemoteNode remoteNode = CommonUtils.getRemoteNode(identifier);

		SpringCloudCoordinator handler = new SpringCloudCoordinator();
		handler.setStatefully(this.statefully);
		handler.setIdentifier(identifier);
		handler.setEnvironment(this.environment);

		RemoteCoordinator participant = (RemoteCoordinator) Proxy.newProxyInstance(
				SpringCloudCoordinator.class.getClassLoader(), new Class[] { RemoteCoordinator.class }, handler);

		registry.putRemoteNode(remoteAddr, remoteNode);

		return participant;
	}

	public RemoteCoordinator getConsumeCoordinatorStateless(String identifier) {
		RemoteCoordinatorRegistry registry = RemoteCoordinatorRegistry.getInstance();
		if (StringUtils.isBlank(identifier)) {
			return null;
		}

		String application = CommonUtils.getApplication(identifier);
		RemoteCoordinator participant = registry.getParticipant(application);
		if (participant != null) {
			return participant;
		}

		RemoteAddr remoteAddr = CommonUtils.getRemoteAddr(identifier);
		RemoteNode remoteNode = CommonUtils.getRemoteNode(identifier);

		SpringCloudCoordinator handler = new SpringCloudCoordinator();
		handler.setStatefully(this.statefully);
		handler.setIdentifier(identifier);
		handler.setEnvironment(this.environment);

		participant = (RemoteCoordinator) Proxy.newProxyInstance(SpringCloudCoordinator.class.getClassLoader(),
				new Class[] { RemoteCoordinator.class }, handler);

		registry.putRemoteNode(remoteAddr, remoteNode);
		registry.putParticipant(application, participant);

		return participant;
	}

	public CompensableLoadBalancerInterceptor getLoadBalancerInterceptor() {
		return this.interceptors.get();
	}

	public void setLoadBalancerInterceptor(CompensableLoadBalancerInterceptor interceptor) {
		this.interceptors.set(interceptor);
	}

	public void removeLoadBalancerInterceptor() {
		this.interceptors.remove();
	}

	public RestTemplate getRestTemplate() {
		return restTemplate;
	}

	public void setRestTemplate(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public CompensableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public boolean isStatefully() {
		return statefully;
	}

	public void setStatefully(boolean statefully) {
		this.statefully = statefully;
	}

	public Environment getEnvironment() {
		return environment;
	}

	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

}
