package dev.devfest.judge;

import dev.devfest.contest.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EvaluationWorker {

  private final QueueStore queue;
  private final ContestService contest;
  private final JudgeClient judge;
  private final boolean enabled;
  private final int concurrency;
  private final ExecutorService pool;
  private final java.util.concurrent.atomic.AtomicInteger active =
    new java.util.concurrent.atomic.AtomicInteger();

  public EvaluationWorker(
    QueueStore queue,
    ContestService contest,
    JudgeClient judge,
    @Value("${app.worker-enabled}") boolean enabled,
    @Value("${app.worker-concurrency}") int concurrency
  ) {
    this.queue = queue;
    this.contest = contest;
    this.judge = judge;
    this.enabled = enabled;
    this.concurrency = Math.max(1, Math.min(8, concurrency));
    pool = Executors.newFixedThreadPool(this.concurrency);
  }

  @Scheduled(fixedDelay = 500)
  public void tick() {
    if (!enabled) return;
    while (active.get() < concurrency) {
      var job = queue.claim(concurrency);
      if (job == null) return;
      active.incrementAndGet();
      pool.submit(() -> {
        try {
          evaluate(job);
        } catch (Exception e) {
          queue.retry(job);
        } finally {
          active.decrementAndGet();
        }
      });
    }
  }

  private void evaluate(QueueStore.Job job) throws Exception {
    var problem = contest.problem(job.problem(), job.version());
    judge.verifyRuntime(ProblemData.Language.valueOf(job.language()));
    boolean run = job.kind().equals("RUN");
    var tests = run
      ? List.of(new ProblemData.TestCase(job.stdin(), ""))
      : problem.tests();
    int passed = 0;
    JudgeClient.Result result = null;
    for (int i = 0; i < tests.size(); i++) {
      if (!queue.renew(job)) return;
      String token = queue.token(job, i);
      if (token == null) {
        token = judge.dispatch(
          job.source(),
          job.languageId(),
          tests.get(i).input(),
          problem.cpuSeconds(),
          problem.memoryKb()
        );
        queue.token(job, i, token);
        token = queue.token(job, i);
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      while (true) {
        if (!queue.renew(job)) return;
        result = judge.poll(token);
        if (result.status() > 2) break;
        if (System.nanoTime() > deadline) throw new IllegalStateException(
          "Execution timeout"
        );
        Thread.sleep(1000);
      }
      String verdict = Verdicts.verdict(
        result,
        tests.get(i).output(),
        run,
        problem.memoryKb()
      );
      if (verdict.equals("SYSTEM_ERROR")) {
        queue.forgetToken(job, i);
        throw new IllegalStateException("Provider failure");
      }
      if (!verdict.equals("ACCEPTED")) {
        queue.finish(job, verdict, passed, result);
        return;
      }
      passed++;
    }
    queue.finish(job, "ACCEPTED", passed, result);
  }

  @PreDestroy
  void close() {
    pool.shutdownNow();
  }
}
