/*
 * Copyright 2012 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linkedin.parseq;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.linkedin.parseq.internal.InternalUtil;
import com.linkedin.parseq.internal.TaskLogger;
import com.linkedin.parseq.promise.Promise;
import com.linkedin.parseq.promise.PromiseListener;
import com.linkedin.parseq.promise.Promises;
import com.linkedin.parseq.promise.SettablePromise;
import com.linkedin.parseq.trace.Related;
import com.linkedin.parseq.trace.ShallowTrace;
import com.linkedin.parseq.trace.Trace;

/**
 * A task represents a deferred execution that also contains its resulting
 * value. In addition, tasks include some tracing information that can be
 * used with various trace printers.
 * <p/>
 * Tasks should generally be run using either an {@link Engine} or a
 * {@link Context}. They should not be run directly.
 *
 * @author Chris Pettitt (cpettitt@linkedin.com)
 * @author Jaroslaw Odzga (jodzga@linkedin.com)
 */
public interface Task<T> extends Promise<T>, Cancellable
{
  /**
   * Returns the name of this task.
   *
   * @return the name of this task
   */
  public String getName();

  /**
   * Returns the priority for this task.
   *
   * @return the priority for this task.
   */
  int getPriority();

  /**
   * Overrides the priority for this task. Higher priority tasks will be
   * executed before lower priority tasks in the same context. In most cases,
   * the default priority is sufficient.
   * <p/>
   * The default priority is 0. Use {@code priority < 0} to make a task
   * lower priority and {@code priority > 0} to make a task higher
   * priority.
   * <p/>
   * If the task has already started execution the priority cannot be
   * changed.
   *
   * @param priority the new priority for the task.
   * @return {@code true} if the priority was set; otherwise {@code false}.
   * @throws IllegalArgumentException if the priority is out of range
   * @see Priority
   */
  boolean setPriority(int priority);

  /**
   * Attempts to run the task with the given context. This method is
   * reserved for use by {@link Engine} and {@link Context}.
   *
   * @param context the context to use while running this step
   * @param taskLogger the logger used for task events
   * @param parent the parent of this task
   * @param predecessors that lead to the execution of this task
   */
  void contextRun(Context context, TaskLogger taskLogger,
                  Task<?> parent, Collection<Task<?>> predecessors);


  /**
   * Returns the ShallowTrace for this task. The ShallowTrace will be
   * a point-in-time snapshot and may change over time until the task is
   * completed.
   *
   * @return the ShallowTrace related to this task
   */
  ShallowTrace getShallowTrace();

  /**
   * Returns the Trace for this task. The Trace will be a point-in-time snapshot
   * and may change over time until the task is completed.
   *
   * @return the Trace related to this task
   */
  Trace getTrace();

  /**
   * Returns the set of relationships of this task. The parent relationships are not included.
   *
   * @see com.linkedin.parseq.trace.Relationship the available relationships
   * @return the set of relationships of this task.
   */
  Set<Related<Task<?>>> getRelationships();
  
  /**
   * Creates a new Task by applying a function to the successful result of this Task.
   * If this Task is completed with an exception then the new Task will also contain that exception.
   * 
   * @param desc description of a mapping function, it will show up in a trace
   * @param f function to be applied to successful result of this Task.
   * @return a new Task which will apply given function on result of successful completion of this task
   */
  default <R> Task<R> map(final String desc, final Function<T, R> f) {
    final Task<T> that = this;
    Task<R> result = Tasks.seq(that, Tasks.callable("map: " + desc, (ThrowableCallable<R>) () -> f.apply(that.get())));
    return result;
  }

  /**
   * Creates a new Task by applying a function to the successful result of this Task and
   * returns the result of a function as the new Task.
   * If this Task is completed with an exception then the new Task will also contain that exception.
   * 
   * @param desc description of a mapping function, it will show up in a trace
   * @param f function to be applied to successful result of this Task.
   * @return a new Task which will apply given function on result of successful completion of this task
   */
  default <R> Task<R> flatMap(final String desc, final Function<T, Task<R>> f) {
    final Task<T> that = this;
    return Tasks.seq(that, new BaseTask<R>("flatMap: " + desc) {
      @Override
      protected Promise<R> run(Context context) throws Throwable {
        Task<R> fm = f.apply(that.get());
        context.run(fm);
        return fm;
      }
    });
  }

  /**
   * Applies the side-effecting function to the result of this Task, and returns
   * a new Task with the result of this Task to allow fluent chaining.
   * 
   * @param desc description of a side-effecting function, it will show up in a trace
   * @param m side-effecting function
   * @return a new Task with the result of this Task
   */
  default Task<T> andThen(final String desc, final Consumer<T> m) {
    final Task<T> that = this;
    return Tasks.seq(that, Tasks.callable("andThen: " + desc, (ThrowableCallable<T>) () -> {
      T value = that.get();
      m.accept(value);
      return value;
    }));
  }

  /**
   * Creates a new Task that will handle any Throwable that this Task might throw
   * or Task cancellation.
   * If this task completes successfully, then recovery function is not invoked.
   * 
   * @param desc description of a recovery function, it will show up in a trace
   * @param f recovery function which can complete Task with a value depending on
   *        Throwable thrown by this Task 
   * @return a new Task which can recover from Throwable thrown by this Task
   */
  default Task<T> recover(final String desc, final Function<Throwable, T> f) {
    final Task<T> that = this;
    return Tasks.seq(that, Tasks.callable("recover: " + desc, (ThrowableCallable<T>) () -> {
      if (that.isFailed()) {
        return f.apply(that.getError());
      } else {
        return that.get();
      }
    }));
  }

  /**
   * Creates a new Task that will handle any Throwable that this Task might throw
   * or Task cancellation. If this task completes successfully,
   * then recovery function is not invoked. Task returned by recovery function
   * will become a new result of this Task. This means that if recovery function fails,
   * then result of this task will fail with a Throwable from recovery function.
   * 
   * @param desc description of a recovery function, it will show up in a trace
   * @param f recovery function which can return Task which will become a new result of
   * this Task
   * @return a new Task which can recover from Throwable thrown by this Task or cancellation
   */
  default Task<T> recoverWith(final String desc, final Function<Throwable, Task<T>> f) {
    final Task<T> that = this;
    return Tasks.seq(that, new BaseTask<T>("recoverWith: " + desc) {
      @Override
      protected Promise<T> run(Context context) throws Throwable {
        if (that.isFailed()) {
          Task<T> recovery = f.apply(that.getError());
          context.run(recovery);
          return recovery;
        } else {
          return that;
        }
      }
    });
  }

  /**
   * Creates a new Task that will handle any Throwable that this Task might throw
   * or Task cancellation. If this task completes successfully,
   * then fall-back function is not invoked. If Task returned by fall-back function
   * completes successfully with a value, then that value becomes a result of this
   * Task. If Task returned by fall-back function fails with a Throwable or is cancelled,
   * then this Task will fail with the original Throwable, not the one coming from
   * the fall-back function's Task.
   * 
   * @param desc description of a recovery function, it will show up in a trace
   * @param f recovery function which can return Task which will become a new result of
   * this Task
   * @return a new Task which can recover from Throwable thrown by this Task or cancellation
   */
  default Task<T> fallBackTo(final String desc, final Function<Throwable, Task<T>> f) {
    final Task<T> that = this;
    return Tasks.seq(that, new BaseTask<T>("fallBackTo: " + desc) {
      @Override
      protected Promise<T> run(Context context) throws Throwable {
        if (that.isFailed()) {
          Task<T> recovery = f.apply(that.getError()).recoverWith("fallBackRecovery", x -> that);
          context.run(recovery);
          return recovery;
        } else {
          return that;
        }
      }
    });
  }

  /**
   * Creates a new task that wraps this task. If this task finishes
   * before the timeout occurs then wrapped task takes on the value of this task.
   * If this task does not complete in the given time then wrapped task will
   * have a TimeoutException. The wrapped task may be cancelled when a timeout
   * occurs.
   *
   * @param desc description of a timeout function, it will show up in a trace
   * @param time the time to wait before timing out
   * @param unit the units for the time
   * @param <T> the value type for the task
   * @return the new Task with a timeout
   */
  default Task<T> within(final String desc, final long time, final TimeUnit unit)
  {
    return new TimeoutWithErrorTask<T>("within: " + desc, time, unit, this);
  }

  /**
   * Combines this Task with passed in Task and calls function on a result of
   * the two. If either of tasks fail, then resulting task will also fail with
   * propagated Throwable. If both tasks fail, then resulting task fails with
   * MultiException containing Throwables from both tasks. 
   * @param desc description of a join function, it will show up in a trace
   * @param t Task this Task needs to join with
   * @param f function to be called on successful completion of both tasks
   * @return a new Task which will apply given function on result of successful completion of both tasks
   */
  default <R, U> Task<U> join(final String desc, final Task<R> t, BiFunction<T, R, U> f) {
    final Task<T> that = this;
    return new BaseTask<U>("join: " + desc) {
      @Override
      protected Promise<? extends U> run(Context context) throws Throwable {
        final SettablePromise<U> result = Promises.settable();
        final PromiseListener<?> listener = x -> {
          if (!that.isFailed() && !t.isFailed()) {
            result.done(f.apply(that.get(), t.get()));
          }
          else if (that.isFailed() && t.isFailed()) {
            result.fail(new MultiException("Multiple errors in 'join' task.", Arrays.asList(that.getError(), t.getError())));
          } else if (that.isFailed()) {
            result.fail(that.getError());
          } else {
            result.fail(t.getError());
          }
        };
        InternalUtil.after(listener, new Task<?>[]{ that, t});
        context.run(that);
        context.run(t);
        return result;
      }
    };
  }
}
