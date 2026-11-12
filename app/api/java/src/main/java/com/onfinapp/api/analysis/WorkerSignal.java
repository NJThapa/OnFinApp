package com.onfinapp.api.analysis;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/** Lets a request wake the worker as soon as it queues a job, instead of waiting for the next poll. */
@Component
public class WorkerSignal {

    private final Semaphore pending = new Semaphore(0);

    public void signal() {
        if (pending.availablePermits() == 0) {
            pending.release();
        }
    }

    /** Returns when signalled or after the timeout, whichever comes first. */
    public void await(long timeoutMs) throws InterruptedException {
        if (pending.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            pending.drainPermits();
        }
    }
}
