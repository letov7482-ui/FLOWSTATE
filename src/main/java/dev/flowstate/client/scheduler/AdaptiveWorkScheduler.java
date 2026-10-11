package dev.flowstate.client.scheduler;

import dev.flowstate.client.config.PerformanceMode;
import dev.flowstate.client.performance.FrameTimeMonitor;
import dev.flowstate.client.performance.FrameTimeMonitor.Snapshot;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Ограниченный планировщик необязательных задач самого мода.
 * - Всё выполняется на вызывающем потоке (клиентский тик). Никаких потоков и блокировок.
 * - Очередь ограничена MAX_TASKS, дубликаты имён отклоняются.
 * - В MAX_FPS диагностические задачи отключаются полностью, и планировщик даже
 *   не обращается к статистике (нулевой накладной расход сверх цикла задач).
 * - В SMART_BALANCE частота диагностики снижается при p95 выше целевого.
 */
public final class AdaptiveWorkScheduler {

    public interface Task {
        String name();
        /** true — диагностическая задача: в MAX_FPS пропускается, при нагрузке замедляется. */
        boolean diagnostic();
        long baseIntervalNanos();
        /** Контракт: не бросает исключений и не блокирует. */
        void run(long nowNanos);
    }

    public static final int MAX_TASKS = 16;
    private static final int MAX_TASKS_PER_TICK = 2; // защита от пакетного выполнения

    private final LongSupplier clock;
    private final Task[] tasks = new Task[MAX_TASKS];
    private final long[] lastRunNanos = new long[MAX_TASKS];
    private int taskCount;
    private String lastRegisterError;

    public AdaptiveWorkScheduler(LongSupplier clock) {
        this.clock = clock;
        Arrays.fill(lastRunNanos, Long.MIN_VALUE);
    }

    /** @return true, если задача зарегистрирована. */
    public boolean register(Task task) {
        if (task == null || task.name() == null || taskCount >= MAX_TASKS) {
            lastRegisterError = "rejected: null, missing name or task limit reached";
            return false;
        }
        for (int i = 0; i < taskCount; i++) {
            if (tasks[i].name().equals(task.name())) {
                lastRegisterError = "rejected: duplicate name '" + task.name() + "'";
                return false;
            }
        }
        tasks[taskCount] = task;
        lastRunNanos[taskCount] = Long.MIN_VALUE;
        taskCount++;
        return true;
    }

    public String lastRegisterError() {
        return lastRegisterError;
    }

    public int taskCount() {
        return taskCount;
    }

    /** Запускает созревшие задачи. Вызывать на клиентском тике. Никогда не блокирует. */
    public void runDue(FrameTimeMonitor monitor, PerformanceMode mode, double targetP95Ms) {
        long now = clock.getAsLong();
        // В MAX_FPS статистика не запрашивается вовсе.
        double diagnosticFactor = mode == PerformanceMode.MAX_FPS
                ? 1.0
                : throttleFactor(monitor, mode, targetP95Ms);
        int budget = MAX_TASKS_PER_TICK;
        for (int i = 0; i < taskCount && budget > 0; i++) {
            Task task = tasks[i];
            if (task.diagnostic() && mode == PerformanceMode.MAX_FPS) continue;
            long interval = (long) (task.baseIntervalNanos() * (task.diagnostic() ? diagnosticFactor : 1.0));
            if (interval < 1) interval = 1;
            long last = lastRunNanos[i];
            if (last != Long.MIN_VALUE && now - last < interval) continue;
            lastRunNanos[i] = now;
            task.run(now);
            budget--;
        }
    }

    private double throttleFactor(FrameTimeMonitor monitor, PerformanceMode mode, double targetP95Ms) {
        if (mode == PerformanceMode.ULTRA_SMOOTH) return 1.0; // полная диагностика
        // SMART_BALANCE: чем выше p95 относительно цели, тем реже необязательные задачи.
        Snapshot s = monitor.stats();
        double p95 = s == null ? 0.0 : s.p95Ms;
        if (p95 <= 0 || targetP95Ms <= 0) return 1.0;
        double ratio = p95 / targetP95Ms;
        if (ratio <= 1.0) return 1.0;
        return Math.min(4.0, 1.0 + (ratio - 1.0) * 2.0);
    }

    /** Сброс таймеров при смене мира; сами задачи остаются зарегистрированными. */
    public void resetTimers() {
        Arrays.fill(lastRunNanos, Long.MIN_VALUE);
    }
}
