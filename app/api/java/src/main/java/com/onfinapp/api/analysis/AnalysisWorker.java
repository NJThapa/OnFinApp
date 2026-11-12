package com.onfinapp.api.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/** Background thread that runs generation jobs one at a time. */
@Component
public class AnalysisWorker {

    private static final long ERROR_DELAY_MS = 10_000;
    private static final long STOP_WAIT_MS = 5_000;
    private static final Logger log = LoggerFactory.getLogger(AnalysisWorker.class);

    private final AnalysisGenerator generator;
    private final WorkerSignal signal;
    private final AnalysisProperties properties;
    private volatile boolean running;
    private Thread thread;

    public AnalysisWorker(AnalysisGenerator generator, WorkerSignal signal, AnalysisProperties properties) {
        this.generator = generator;
        this.signal = signal;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.workerEnabled()) {
            log.info("The analysis worker is disabled; this API queues jobs but does not run them");
            return;
        }
        running = true;
        thread = Thread.ofPlatform().daemon().name("analysis-worker").start(this::loop);
        log.info("The analysis worker is running; it looks for jobs every {} ms", properties.workerPollMs());
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread.join(STOP_WAIT_MS);
        }
    }

    private void loop() {
        while (running) {
            try {
                if (!generator.processNext()) {
                    signal.await(properties.workerPollMs());
                }
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                // Usually the database being away: wait, then carry on.
                log.warn("The analysis worker hit an error and will try again in {} s: {}",
                        ERROR_DELAY_MS / 1000, e.getMessage());
                try {
                    Thread.sleep(ERROR_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }
}
