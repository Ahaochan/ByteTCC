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
package org.bytesoft.bytetcc.supports.spring;

import org.aopalliance.aop.Advice;
import org.bytesoft.compensable.Compensable;
import org.springframework.aop.Advisor;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.config.BeanPostProcessor;

public class TransactionAdviceOrderStraightener implements BeanPostProcessor {

	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean; // this.switchAdvisorOrderIfNecessary(bean, beanName);
	}

	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		this.switchAdvisorOrderIfNecessary(bean, beanName);
		return bean;
	}

	private void switchAdvisorOrderIfNecessary(Object bean, String beanName) {
		if (org.springframework.aop.framework.Advised.class.isInstance(bean) == false) {
			return;
		}

		org.springframework.aop.framework.Advised advised = (org.springframework.aop.framework.Advised) bean;
//		org.springframework.aop.framework.AdvisedSupport advised = (org.springframework.aop.framework.AdvisedSupport) bean;
		Class<?> targetClass = advised.getTargetClass();

		String message = //
				String.format("Bean '%s' may be proxied multiple times, its actual class cannot be obtained.", beanName);
		if (org.springframework.cglib.proxy.Proxy.isProxyClass(targetClass)) {
			throw new FatalBeanException(message);
		} else if (net.sf.cglib.proxy.Proxy.isProxyClass(targetClass)) {
			throw new FatalBeanException(message);
		} else if (java.lang.reflect.Proxy.isProxyClass(targetClass)) {
			throw new FatalBeanException(message);
		}

		if (targetClass == null || targetClass.getAnnotation(Compensable.class) == null) {
			return;
		} // end-if (targetClass == null || targetClass.getAnnotation(Compensable.class) == null)

		Advisor[] advisors = advised.getAdvisors();

		int compensableIndex = -1;
		int transactionIndex = -1;
		for (int i = 0; i < advisors.length; i++) {
			Advisor advisor = advisors[i];
			Advice advice = advisor.getAdvice();
			if (org.springframework.aop.aspectj.AspectJAroundAdvice.class.isInstance(advice)) {
				org.springframework.aop.aspectj.AspectJAroundAdvice around = //
						(org.springframework.aop.aspectj.AspectJAroundAdvice) advice;
				Class<?> declaringClass = around.getAspectJAdviceMethod().getDeclaringClass();
				if (org.bytesoft.bytetcc.supports.spring.CompensableMethodInterceptor.class.equals(declaringClass)) {
					if (compensableIndex != -1) {
						throw new FatalBeanException(
								"There are more than one advice(org.bytesoft.bytetcc.supports.spring.CompensableMethodInterceptor) defined!");
					}
					compensableIndex = i;
				}
			} else if (org.bytesoft.bytetcc.supports.spring.CompensableMethodInterceptor.class.isInstance(advice)) {
				if (compensableIndex != -1) {
					throw new FatalBeanException(
							"There are more than one advice(org.bytesoft.bytetcc.supports.spring.CompensableMethodInterceptor) defined!");
				}
				compensableIndex = i;
			} else if (org.springframework.transaction.interceptor.TransactionInterceptor.class.isInstance(advice)) {
				transactionIndex = i;
			}
		}

		if (transactionIndex == -1 || compensableIndex == -1) {
			throw new FatalBeanException("There may be an error in the transaction configuration.");
		}

		if (transactionIndex != -1 && compensableIndex != -1 && transactionIndex < compensableIndex) {
//			Advisor advisor = advisors[transactionIndex];
//			advisors[transactionIndex] = advisors[compensableIndex];
//			advisors[compensableIndex] = advisor;
			Advisor transactionAdvisor = advisors[transactionIndex];
			Advisor compensableAdvisor = advisors[compensableIndex];
			advised.removeAdvisor(transactionIndex);
			advised.addAdvice(transactionIndex, compensableAdvisor.getAdvice());
			advised.removeAdvisor(compensableIndex);
			advised.addAdvice(compensableIndex, transactionAdvisor.getAdvice());
		}
	}

}
