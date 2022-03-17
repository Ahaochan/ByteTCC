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
package org.bytesoft.compensable;

import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

// 为每个线程封装一个CompensableInvocation栈, 将CompensableInvocation进行入栈出栈操作
public final class CompensableInvocationRegistry {
	static final CompensableInvocationRegistry instance = new CompensableInvocationRegistry();

	private Map<Thread, Stack<CompensableInvocation>> invocationMap = new ConcurrentHashMap<Thread, Stack<CompensableInvocation>>();

	private CompensableInvocationRegistry() {
	}

	public void register(CompensableInvocation invocation) {
		// 获取当前线程的CompensableInvocation栈
		Thread current = Thread.currentThread();
		Stack<CompensableInvocation> stack = this.invocationMap.get(current);
		if (stack == null) {
			stack = new Stack<CompensableInvocation>();
			this.invocationMap.put(current, stack);
		}
		// 将CompensableInvocation压入栈顶
		stack.push(invocation);
	}

	public CompensableInvocation getCurrent() {
		// 获取当前线程的CompensableInvocation栈
		Thread current = Thread.currentThread();
		Stack<CompensableInvocation> stack = this.invocationMap.get(current);
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		// 获取栈顶的CompensableInvocation, 但是不弹出栈
		return stack.peek();
	}

	public CompensableInvocation unRegister() {
		// 获取当前线程的CompensableInvocation栈
		Thread current = Thread.currentThread();
		Stack<CompensableInvocation> stack = this.invocationMap.get(current);
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		// 获取栈顶的CompensableInvocation, 并弹出栈
		CompensableInvocation invocation = stack.pop();
		if (stack.isEmpty()) {
			this.invocationMap.remove(current);
		}
		return invocation;
	}

	public static CompensableInvocationRegistry getInstance() {
		// 饿汉单例模式
		return instance;
	}
}
