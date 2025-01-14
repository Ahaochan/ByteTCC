/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
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

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.compensable.Compensable;
import org.bytesoft.compensable.CompensableCancel;
import org.bytesoft.compensable.CompensableConfirm;
import org.bytesoft.compensable.RemotingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class CompensableAnnotationConfigValidator
		implements SmartInitializingSingleton, ApplicationContextAware, BeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableAnnotationConfigValidator.class);

	private ApplicationContext applicationContext;
	private BeanFactory beanFactory;

	public void afterSingletonsInstantiated() {
		String[] beanNameArray = this.applicationContext.getBeanDefinitionNames();
		BeanDefinitionRegistry registry = (BeanDefinitionRegistry) this.beanFactory;

		Map<String, Compensable> compensables = new HashMap<String, Compensable>();
		Map<String, Class<?>> otherServiceMap = new HashMap<String, Class<?>>();
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		for (int i = 0; beanNameArray != null && i < beanNameArray.length; i++) {
			String beanName = beanNameArray[i];
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);
			String className = beanDef.getBeanClassName();

			if (StringUtils.isBlank(className)) {
				continue;
			}

			Class<?> clazz = null;
			try {
				clazz = cl.loadClass(className);
			} catch (Exception ex) {
				logger.debug("Cannot load class {}, beanId= {}!", className, beanName, ex);
				continue;
			}

			// 找到修饰了@Compensable注解的Bean
			Compensable compensable = null;
			try {
				compensable = clazz.getAnnotation(Compensable.class);
			} catch (RuntimeException rex) {
				logger.warn("Error occurred while getting @Compensable annotation, class= {}!", clazz, rex);
			}

			if (compensable == null) {
				otherServiceMap.put(beanName, clazz);
				continue;
			}

			// 校验, @Compensable的interfaceClass属性必须用接口
			Class<?> interfaceClass = compensable.interfaceClass();
			if (interfaceClass.isInterface() == false) {
				throw new IllegalStateException("Compensable's interfaceClass must be a interface.");
			}

			// 遍历这个接口里的所有方法
			Method[] methodArray = interfaceClass.getDeclaredMethods();
			for (int j = 0; j < methodArray.length; j++) {
				Method interfaceMethod = methodArray[j];
				String methodName = interfaceMethod.getName();
				Class<?>[] parameterTypes = interfaceMethod.getParameterTypes();
				Method method = null;
				try {
					// 拿到接口实现类的方法Method对象
					method = clazz.getMethod(methodName, parameterTypes);
				} catch (NoSuchMethodException ex) {
					throw new FatalBeanException(String.format(
							"Compensable-service(%s) does not implement method '%s' specified by the interfaceClass.", beanName,
							methodName));
				}
				// 把具体实现方法的类class对象和方法method对象传入, 进行校验
				this.validateSimplifiedCompensable(method, clazz);
				// 方法不允许抛出RemotingException异常
				this.validateDeclaredRemotingException(method, clazz);
				// 必须有@Transactional修饰, 并且事务的传播级别要求是REQUIRED、MANDATORY、REQUIRES_NEW
				this.validateTransactionalPropagation(method, clazz);
			}

			compensables.put(beanName, compensable);
		}

		Iterator<Map.Entry<String, Compensable>> itr = compensables.entrySet().iterator();
		while (itr.hasNext()) {
			// 遍历所有@Compensable注解
			Map.Entry<String, Compensable> entry = itr.next();
			Compensable compensable = entry.getValue();
			Class<?> interfaceClass = compensable.interfaceClass();
			String confirmableKey = compensable.confirmableKey();
			String cancellableKey = compensable.cancellableKey();
			// 对confirmableKey属性进行处理
			if (StringUtils.isNotBlank(confirmableKey)) {
				if (compensables.containsKey(confirmableKey)) {
					throw new FatalBeanException(
							String.format("The confirm bean(id= %s) cannot be a compensable service!", confirmableKey));
				}
				// 将confirmableKey作为BeanName, 去找到指定的Bean
				Class<?> clazz = otherServiceMap.get(confirmableKey);
				if (clazz == null) {
					throw new IllegalStateException(String.format("The confirm bean(id= %s) is not exists!", confirmableKey));
				}

				// 遍历这个接口里的所有方法
				Method[] methodArray = interfaceClass.getDeclaredMethods();
				for (int j = 0; j < methodArray.length; j++) {
					Method interfaceMethod = methodArray[j];
					String methodName = interfaceMethod.getName();
					Class<?>[] parameterTypes = interfaceMethod.getParameterTypes();
					Method method = null;
					try {
						// 拿到BeanName为confirmableKey的接口实现类的方法Method对象
						method = clazz.getMethod(methodName, parameterTypes);
					} catch (NoSuchMethodException ex) {
						throw new FatalBeanException(String.format(
								"Confirm-service(%s) does not implement method '%s' specified by the interfaceClass.",
								confirmableKey, methodName));
					}
					// 把BeanName为confirmableKey的接口实现类class对象和方法method对象传入, 进行校验
					// 方法不允许抛出RemotingException异常
					this.validateDeclaredRemotingException(method, clazz);
					// 必须有@Transactional修饰, 并且事务的传播级别要求是REQUIRED、MANDATORY、REQUIRES_NEW
					this.validateTransactionalPropagation(method, clazz);
					this.validateTransactionalRollbackFor(method, clazz, confirmableKey);
				}
			} // end-if (StringUtils.isNotBlank(confirmableKey))

			// 对cancellableKey属性进行处理
			if (StringUtils.isNotBlank(cancellableKey)) {
				if (compensables.containsKey(cancellableKey)) {
					throw new FatalBeanException(
							String.format("The cancel bean(id= %s) cannot be a compensable service!", confirmableKey));
				}
				// 将cancellableKey作为BeanName, 去找到指定的Bean
				Class<?> clazz = otherServiceMap.get(cancellableKey);
				if (clazz == null) {
					throw new IllegalStateException(String.format("The cancel bean(id= %s) is not exists!", cancellableKey));
				}
				// 遍历这个接口里的所有方法
				Method[] methodArray = interfaceClass.getDeclaredMethods();
				for (int j = 0; j < methodArray.length; j++) {
					Method interfaceMethod = methodArray[j];
					String methodName = interfaceMethod.getName();
					Class<?>[] parameterTypes = interfaceMethod.getParameterTypes();

					Method method = null;
					try {
						// 拿到BeanName为cancellableKey的接口实现类的方法Method对象
						method = clazz.getMethod(methodName, parameterTypes);
					} catch (NoSuchMethodException ex) {
						throw new FatalBeanException(String.format(
								"Cancel-service(%s) does not implement method '%s' specified by the interfaceClass.",
								confirmableKey, methodName));
					}
					// 把BeanName为cancellableKey的接口实现类class对象和方法method对象传入, 进行校验
					// 方法不允许抛出RemotingException异常
					this.validateDeclaredRemotingException(method, clazz);
					// 必须有@Transactional修饰, 并且事务的传播级别要求是REQUIRED、MANDATORY、REQUIRES_NEW
					this.validateTransactionalPropagation(method, clazz);
					this.validateTransactionalRollbackFor(method, clazz, cancellableKey);
				}
			} // end-if (StringUtils.isNotBlank(cancellableKey))

		}
	}

	private void validateSimplifiedCompensable(Method method, Class<?> clazz) throws IllegalStateException {
		// 入参是具体实现方法的类class对象和方法method对象
		Compensable compensable = clazz.getAnnotation(Compensable.class);
		Class<?> interfaceClass = compensable.interfaceClass();
		Method[] methods = interfaceClass.getDeclaredMethods();
		if (compensable.simplified() == false) {
			// 如果@Compensable注解的simplified属性为false, 就不做下面的校验了
			return;
		} else if (method.getAnnotation(CompensableConfirm.class) != null) {
			throw new FatalBeanException(
					String.format("The try method(%s) can not be the same as the confirm method!", method));
		} else if (method.getAnnotation(CompensableCancel.class) != null) {
			throw new FatalBeanException(String.format("The try method(%s) can not be the same as the cancel method!", method));
		} else if (methods != null && methods.length > 1) {
			throw new FatalBeanException(String.format(
					"The interface bound by @Compensable(simplified= true) supports only one method, class= %s!", clazz));
		}

		Class<?>[] parameterTypes = method.getParameterTypes();
		Method[] methodArray = clazz.getDeclaredMethods();

		CompensableConfirm confirmable = null;
		CompensableCancel cancellable = null;
		for (int i = 0; i < methodArray.length; i++) {
			Method element = methodArray[i];
			Class<?>[] paramTypes = element.getParameterTypes();
			CompensableConfirm confirm = element.getAnnotation(CompensableConfirm.class);
			CompensableCancel cancel = element.getAnnotation(CompensableCancel.class);
			if (confirm == null && cancel == null) {
				continue;
			} else if (Arrays.equals(parameterTypes, paramTypes) == false) {
				throw new FatalBeanException(
						String.format("The parameter types of confirm/cancel method({}) is different from the try method({})!",
								element, method));
			} else if (confirm != null) {
				if (confirmable != null) {
					throw new FatalBeanException(
							String.format("There are more than one confirm method specified, class= %s!", clazz));
				} else {
					confirmable = confirm;
				}
			} else if (cancel != null) {
				if (cancellable != null) {
					throw new FatalBeanException(
							String.format("There are more than one cancel method specified, class= %s!", clazz));
				} else {
					cancellable = cancel;
				}
			}

		}

	}

	private void validateDeclaredRemotingException(Method method, Class<?> clazz) throws IllegalStateException {
		Class<?>[] exceptionTypeArray = method.getExceptionTypes();

		boolean located = false;
		for (int i = 0; i < exceptionTypeArray.length; i++) {
			Class<?> exceptionType = exceptionTypeArray[i];
			if (RemotingException.class.isAssignableFrom(exceptionType)) {
				// 方法不允许抛出RemotingException异常
				located = true;
				break;
			}
		}

		if (located) {
			throw new FatalBeanException(String.format(
					"The method(%s) shouldn't be declared to throw a remote exception: org.bytesoft.compensable.RemotingException!",
					method));
		}

	}

	private void validateTransactionalPropagation(Method method, Class<?> clazz) throws IllegalStateException {
		// 方法必须被@Transactional修饰, 表示在Spring事务控制下
		Transactional transactional = method.getAnnotation(Transactional.class);
		if (transactional == null) {
			// 方法没有就从类上找@Transactional注解
			Class<?> declaringClass = method.getDeclaringClass();
			transactional = declaringClass.getAnnotation(Transactional.class);
		}

		if (transactional == null) {
			throw new IllegalStateException(String.format("Method(%s) must be specificed a Transactional annotation!", method));
		}
		// 并且事务的传播级别要求是REQUIRED、MANDATORY、REQUIRES_NEW
		Propagation propagation = transactional.propagation();
		if (Propagation.REQUIRED.equals(propagation) == false //
				&& Propagation.MANDATORY.equals(propagation) == false //
				&& Propagation.REQUIRES_NEW.equals(propagation) == false) {
			throw new IllegalStateException(
					String.format("Method(%s) not support propagation level: %s!", method, propagation.name()));
		}
	}

	private void validateTransactionalRollbackFor(Method method, Class<?> clazz, String beanName) throws IllegalStateException {
		// 方法必须被@Transactional修饰, 表示在Spring事务控制下
		Transactional transactional = method.getAnnotation(Transactional.class);
		if (transactional == null) {
			// 方法没有就从类上找@Transactional注解
			Class<?> declaringClass = method.getDeclaringClass();
			transactional = declaringClass.getAnnotation(Transactional.class);
		}

		if (transactional == null) {
			throw new IllegalStateException(String.format("Method(%s) must be specificed a Transactional annotation!", method));
		}

		// 使用了ByteTCC分布式事务, 就不允许@Transactional设置rollbackForClassName属性
		String[] rollbackForClassNameArray = transactional.rollbackForClassName();
		if (rollbackForClassNameArray != null && rollbackForClassNameArray.length > 0) {
			throw new IllegalStateException(String.format(
					"The transactional annotation on the confirm/cancel class does not support the property rollbackForClassName yet(beanId= %s)!",
					beanName));
		}

		Class<?>[] rollErrorArray = transactional.rollbackFor();

		// 遍历这个方法显式抛出的异常
		Class<?>[] errorTypeArray = method.getExceptionTypes();
		for (int j = 0; errorTypeArray != null && j < errorTypeArray.length; j++) {
			Class<?> errorType = errorTypeArray[j];
			if (RuntimeException.class.isAssignableFrom(errorType)) {
				continue;
			}

			boolean matched = false;
			for (int k = 0; rollErrorArray != null && k < rollErrorArray.length; k++) {
				Class<?> rollbackError = rollErrorArray[k];
				if (rollbackError.isAssignableFrom(errorType)) {
					// 如果@Transactional设置rollbackFor属性里的异常类, 在这个方法显式抛出的异常里不存在, 就抛出异常
					matched = true;
					break;
				}
			}

			if (matched == false) {
				throw new IllegalStateException(
						String.format("The value of Transactional.rollbackFor annotated on method(%s) must includes %s!",
								method, errorType.getName()));
			}
		}
	}

	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

}
