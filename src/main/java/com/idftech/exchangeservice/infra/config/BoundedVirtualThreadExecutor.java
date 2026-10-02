package com.idftech.exchangeservice.infra.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Пул задач дорасчёта на виртуальных потоках с верхней границей одновременного выполнения.
 *
 * <p>Виртуальный поток на задачу сам по себе не ограничивает число одновременных задач, а пачка
 * дорасчёта может быть широкой, и каждая задача открывает транзакцию к PostgreSQL. Без границы широкая
 * пачка исчерпала бы пул соединений и упёрлась в таймауты его ожидания вместо того, чтобы просто
 * подождать в очереди. Граница не даёт этому случиться: задачи сверх лимита блокируются на семафоре и
 * занимают виртуальный поток, а не соединение с БД.
 *
 * <p>Блокировка внутри задачи уместна именно потому, что потоки виртуальные: парковка не занимает
 * ресурс потока ОС, ради которого стоило бы возиться с неблокирующими очередями.
 *
 * <p>Граница действует на <em>все</em> точки входа, а не только на {@link #execute(Runnable)}. Раньше
 * {@code submit}, {@code invokeAll} и {@code invokeAny} делегировали напрямую во внутренний пул в
 * обход семафора: {@code CompletableFuture.supplyAsync} шёл через {@code execute} и всё было хорошо,
 * но любой новый код на {@code submit} молча снял бы ограничение. {@link #wrap} — единственное место,
 * где разрешение берётся и возвращается, поэтому обойти его нельзя.
 */
class BoundedVirtualThreadExecutor implements ExecutorService {

  private final ExecutorService delegate = Executors.newVirtualThreadPerTaskExecutor();
  private final Semaphore permit;

  BoundedVirtualThreadExecutor(int parallelism) {
    this.permit = new Semaphore(Math.max(1, parallelism));
  }

  @Override
  public void execute(Runnable command) {
    delegate.execute(wrap(command));
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return delegate.submit(wrap(task));
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    return delegate.submit(wrap(task), result);
  }

  @Override
  public Future<?> submit(Runnable task) {
    return delegate.submit(wrap(task));
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
      throws InterruptedException {
    return delegate.invokeAll(wrapAll(tasks));
  }

  @Override
  public <T> List<Future<T>> invokeAll(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException {
    return delegate.invokeAll(wrapAll(tasks), timeout, unit);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    return delegate.invokeAny(wrapAll(tasks));
  }

  @Override
  public <T> T invokeAny(
      Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return delegate.invokeAny(wrapAll(tasks), timeout, unit);
  }

  private <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
    List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
    for (Callable<T> task : tasks) {
      wrapped.add(wrap(task));
    }
    return wrapped;
  }

  private Runnable wrap(Runnable command) {
    return () -> {
      acquire();
      try {
        command.run();
      } finally {
        permit.release();
      }
    };
  }

  private <T> Callable<T> wrap(Callable<T> task) {
    return () -> {
      acquire();
      try {
        return task.call();
      } finally {
        permit.release();
      }
    };
  }

  private void acquire() {
    try {
      permit.acquire();
    } catch (InterruptedException e) {
      // Прерывание не должно оставлять семафор без разрешения, иначе пул встанет навсегда.
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for a settlement slot", e);
    }
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
