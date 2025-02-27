/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package io.vertx.core.impl;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.concurrent.FastThreadLocalThread;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Starter;
import io.vertx.core.VertxOptions;
import io.vertx.core.impl.launcher.VertxCommandLauncher;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static io.vertx.core.impl.VertxThread.DISABLE_TCCL;

/**
 * A context implementation that does not hold any specific state.
 *
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
abstract class AbstractContext implements ContextInternal {

  static final String THREAD_CHECKS_PROP_NAME = "vertx.threadChecks";
  static final boolean THREAD_CHECKS = Boolean.getBoolean(THREAD_CHECKS_PROP_NAME);

  static Context context() {
    Thread current = Thread.currentThread();
    if (current instanceof VertxThread) {
      return ((VertxThread) current).context();
    } else if (current instanceof FastThreadLocalThread) {
      return holderLocal.get().ctx;
    }
    return null;
  }

  static class Holder implements BlockedThreadChecker.Task {

    BlockedThreadChecker checker;
    ContextInternal ctx;
    long startTime = 0;
    long maxExecTime = VertxOptions.DEFAULT_MAX_EVENT_LOOP_EXECUTE_TIME;
    TimeUnit maxExecTimeUnit = VertxOptions.DEFAULT_MAX_EVENT_LOOP_EXECUTE_TIME_UNIT;

    @Override
    public long startTime() {
      return startTime;
    }

    @Override
    public long maxExecTime() {
      return maxExecTime;
    }

    @Override
    public TimeUnit maxExecTimeUnit() {
      return maxExecTimeUnit;
    }
  }

  private static FastThreadLocal<Holder> holderLocal = new FastThreadLocal<Holder>() {
    @Override
    protected Holder initialValue() {
      return new Holder();
    }
  };

  /**
   * Emit the {@code event} asynchronously.
   *
   * @param event the event for the {@code handler}
   * @param handler the task to execute with the {@code event} argument
   */
  abstract <E> void emitAsync(E event, Handler<E> handler);

  @Override
  public abstract boolean isEventLoopContext();

  @Override
  public boolean isWorkerContext() {
    return !isEventLoopContext();
  }

  // This is called to execute code where the origin is IO (from Netty probably).
  // In such a case we should already be on an event loop thread (as Netty manages the event loops)
  // but check this anyway, then execute directly
  @Override
  public final void emitFromIO(Handler<Void> handler) {
    emitFromIO(null, handler);
  }

  @Override
  public final void schedule(Handler<Void> task) {
    schedule(null, task);
  }

  @Override
  public final void dispatch(Handler<Void> handler) {
    dispatch(null, handler);
  }

  public final ContextInternal beginDispatch() {
    ContextInternal prev;
    Thread th = Thread.currentThread();
    if (th instanceof VertxThread) {
      prev = ((VertxThread)th).beginDispatch(this);
    } else {
      prev = beginNettyThreadDispatch(th);
    }
    if (!DISABLE_TCCL) {
      th.setContextClassLoader(classLoader());
    }
    return prev;
  }

  private ContextInternal beginNettyThreadDispatch(Thread th) {
    if (th instanceof FastThreadLocalThread) {
      Holder holder = holderLocal.get();
      ContextInternal prev = holder.ctx;
      if (!ContextImpl.DISABLE_TIMINGS) {
        if (holder.checker == null) {
          BlockedThreadChecker checker = owner().blockedThreadChecker();
          holder.checker = checker;
          holder.maxExecTime = owner().maxEventLoopExecTime();
          holder.maxExecTimeUnit = owner().maxEventLoopExecTimeUnit();
          checker.registerThread(th, holder);
        }
        if (holder.ctx == null) {
          holder.startTime = System.nanoTime();
        }
      }
      holder.ctx = this;
      return prev;
    } else {
      throw new IllegalStateException("Uh oh! context executing with wrong thread! " + th);
    }
  }

  public final void endDispatch(ContextInternal prev) {
    Thread th = Thread.currentThread();
    if (!DISABLE_TCCL) {
      th.setContextClassLoader(prev != null ? prev.classLoader() : null);
    }
    if (th instanceof VertxThread) {
      ((VertxThread)th).endDispatch(prev);
    } else {
      endNettyThreadDispatch(th, prev);
    }
  }

  private void endNettyThreadDispatch(Thread th, ContextInternal prev) {
    if (th instanceof FastThreadLocalThread) {
      Holder holder = holderLocal.get();
      holder.ctx = prev;
      if (!ContextImpl.DISABLE_TIMINGS) {
        if (holder.ctx == null) {
          holder.startTime = 0L;
        }
      }
    } else {
      throw new IllegalStateException("Uh oh! context executing with wrong thread! " + th);
    }
  }

  @Override
  public final <T> void dispatch(T event, Handler<T> handler) {
    ContextInternal prev = beginDispatch();
    try {
      handler.handle(event);
    } catch (Throwable t) {
      reportException(t);
    } finally {
      endDispatch(prev);
    }
  }

  static void checkEventLoopThread() {
    Thread current = Thread.currentThread();
    if (!(current instanceof FastThreadLocalThread)) {
      throw new IllegalStateException("Expected to be on Vert.x thread, but actually on: " + current);
    } else if ((current instanceof VertxThread) && ((VertxThread) current).isWorker()) {
      throw new IllegalStateException("Event delivered on unexpected worker thread " + current);
    }
  }

  // Run the task asynchronously on this same context
  @Override
  public final void runOnContext(Handler<Void> handler) {
    try {
      emitAsync(null, handler);
    } catch (RejectedExecutionException ignore) {
      // Pool is already shut down
    }
  }

  @Override
  public final List<String> processArgs() {
    // As we are maintaining the launcher and starter class, choose the right one.
    List<String> processArgument = VertxCommandLauncher.getProcessArguments();
    return processArgument != null ? processArgument : Starter.PROCESS_ARGS;
  }

  @Override
  public final <T> void executeBlockingInternal(Handler<Promise<T>> action, Handler<AsyncResult<T>> resultHandler) {
    Future<T> fut = executeBlockingInternal(action);
    setResultHandler(this, fut, resultHandler);
  }

  @Override
  public <T> void executeBlocking(Handler<Promise<T>> blockingCodeHandler, boolean ordered, Handler<AsyncResult<T>> resultHandler) {
    Future<T> fut = executeBlocking(blockingCodeHandler, ordered);
    setResultHandler(this, fut, resultHandler);
  }

  @Override
  public <T> void executeBlocking(Handler<Promise<T>> blockingCodeHandler, TaskQueue queue, Handler<AsyncResult<T>> resultHandler) {
    Future<T> fut = executeBlocking(blockingCodeHandler, queue);
    setResultHandler(this, fut, resultHandler);
  }

  @Override
  public final <T> void executeBlocking(Handler<Promise<T>> blockingCodeHandler, Handler<AsyncResult<T>> resultHandler) {
    executeBlocking(blockingCodeHandler, true, resultHandler);
  }

  public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler) {
    return executeBlocking(blockingCodeHandler, true);
  }

  @Override
  public <T> PromiseInternal<T> promise() {
    return Future.factory.promise(this);
  }

  @Override
  public <T> PromiseInternal<T> promise(Handler<AsyncResult<T>> handler) {
    if (handler instanceof PromiseInternal) {
      return (PromiseInternal<T>) handler;
    } else {
      PromiseInternal<T> promise = promise();
      promise.future().setHandler(handler);
      return promise;
    }
  }

  @Override
  public <T> Future<T> succeededFuture() {
    return Future.factory.succeededFuture(this);
  }

  @Override
  public <T> Future<T> succeededFuture(T result) {
    return Future.factory.succeededFuture(this, result);
  }

  @Override
  public <T> Future<T> failedFuture(Throwable failure) {
    return Future.factory.failedFuture(this, failure);
  }

  @Override
  public <T> Future<T> failedFuture(String message) {
    return Future.factory.failedFuture(this, message);
  }

  @Override
  public final ContextInternal duplicate() {
    return duplicate(null);
  }

  @SuppressWarnings("unchecked")
  @Override
  public final <T> T get(String key) {
    return (T) contextData().get(key);
  }

  @Override
  public final void put(String key, Object value) {
    contextData().put(key, value);
  }

  @Override
  public final boolean remove(String key) {
    return contextData().remove(key) != null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public final <T> T getLocal(String key) {
    return (T) localContextData().get(key);
  }

  @Override
  public final void putLocal(String key, Object value) {
    localContextData().put(key, value);
  }

  @Override
  public final boolean removeLocal(String key) {
    return localContextData().remove(key) != null;
  }

  static <T> void setResultHandler(ContextInternal ctx, Future<T> fut, Handler<AsyncResult<T>> resultHandler) {
    if (resultHandler != null) {
      fut.setHandler(resultHandler);
    } else {
      fut.setHandler(ar -> {
        if (ar.failed()) {
          ctx.reportException(ar.cause());
        }
      });
    }
  }
}
