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
package org.bytesoft.bytetcc.work;

import javax.resource.spi.work.Work;

import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.transaction.TransactionRecovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 被org.bytesoft.transaction.adapter.ResourceAdapterImpl丢到线程池里去执行
public class CompensableWork implements Work, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(CompensableWork.class);

	static final long SECOND_MILLIS = 1000L;
	private long stopTimeMillis = -1;
	private long delayOfStoping = SECOND_MILLIS * 15;
	private long recoveryInterval = SECOND_MILLIS * 60;

	private volatile boolean initialized = false;

	@javax.inject.Inject
	private CompensableBeanFactory beanFactory;

	private void initializeIfNecessary() {
		// 实现类是org.bytesoft.bytetcc.TransactionRecoveryImpl
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		if (this.initialized == false) {
			try {
				compensableRecovery.startRecovery();
				this.initialized = true;
				// 恢复事务
				compensableRecovery.timingRecover();
			} catch (SecurityException rex) {
				logger.debug("Only the master node can perform the initialization operation!");
			} catch (RuntimeException rex) {
				logger.error("Error occurred while initializing the compensable work.", rex);
			}
		}
	}

	public void run() {
		// 启动就恢复一次事务
		this.initializeIfNecessary();

		long nextRecoveryTime = 0;
		while (this.currentActive()) {
			this.initializeIfNecessary();

			long current = System.currentTimeMillis();
			// 每60秒执行一次
			if (current >= nextRecoveryTime) {
				nextRecoveryTime = current + this.recoveryInterval;

				this.fireGlobalRecovery();
				this.fireBranchRecovery();
			}

			// 每100毫秒执行一次循环
			this.waitForMillis(100L);
		} // end-while (this.currentActive())
	}

	private void fireGlobalRecovery() {
		// 实现类是org.bytesoft.bytetcc.TransactionRecoveryImpl
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		try {
			// 恢复事务
			compensableRecovery.timingRecover();
		} catch (SecurityException rex) {
			logger.debug("Only the master node can perform the global recovery operation!");
		} catch (RuntimeException rex) {
			logger.error(rex.getMessage(), rex);
		}
	}

	private void fireBranchRecovery() {
		// 实现类是org.bytesoft.bytetcc.TransactionRecoveryImpl
		TransactionRecovery compensableRecovery = this.beanFactory.getCompensableRecovery();
		try {
			compensableRecovery.branchRecover();
		} catch (SecurityException rex) {
			logger.debug("Only the branch node can perform the branch recovery operation!");
		} catch (RuntimeException rex) {
			logger.error(rex.getMessage(), rex);
		}
	}

	private void waitForMillis(long millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception ignore) {
			logger.debug(ignore.getMessage(), ignore);
		}
	}

	public void release() {
		this.stopTimeMillis = System.currentTimeMillis() + this.delayOfStoping;
	}

	protected boolean currentActive() {
		return this.stopTimeMillis <= 0 || System.currentTimeMillis() < this.stopTimeMillis;
	}

	public long getDelayOfStoping() {
		return delayOfStoping;
	}

	public long getRecoveryInterval() {
		return recoveryInterval;
	}

	public void setRecoveryInterval(long recoveryInterval) {
		this.recoveryInterval = recoveryInterval;
	}

	public void setDelayOfStoping(long delayOfStoping) {
		this.delayOfStoping = delayOfStoping;
	}

	public CompensableBeanFactory getBeanFactory() {
		return this.beanFactory;
	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
