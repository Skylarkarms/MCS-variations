import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple Thread synchronizer that uses a version-based fairness queue.
 * This synchronizes IS 100% FAIR and SEQUENTIALLY CONSISTENT within it's the synchronized body
 *
 * As the type of synchronization does not rely on the reactive signaling by releasing processes, this methodology cannot support parking type of actions.
 * Instead, relying solely on yielding operations.
 * As this type is a sort of busy-wait, it can support up to ~1200 concurrent Threads, at which point it MAY incur in
 * Lock-holder starvation due to cooperative yielding leading to runqueue inversion.
 *
 * This synchronizer allows less throughput than the {@link FastSynchronizer}.
 * */
public class FairSynchronizer {

    AtomicInteger ticket = new AtomicInteger();
    AtomicInteger done = new AtomicInteger();
    int currentTicket;

    static final int cores = - (Runtime.getRuntime().availableProcessors() / 2);

    public void acquire() {
        int currentTicket = this.ticket.incrementAndGet();
        int d = -1;
        for (boolean yield = false;;) {
            int n_d = done.getAcquire();
            if (d != n_d) {
                d = n_d;
                n_d = n_d + 1 - currentTicket;
                if (n_d == 0) break;
                yield = n_d < cores;
            }
            if (yield) {
                Thread.yield();
            } else {
                Thread.onSpinWait();
            }
        }
        this.currentTicket = currentTicket;
    }

    public void release() {
        done.setRelease(currentTicket);
    }
}
