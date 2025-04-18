import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple Thread synchronizer that uses a version-based fairness queue, with fast-path (lock-free) that omit the version.
 * This synchronizes IS NOT 100% FAIR, but it IS SEQUENTIALLY CONSISTENT in relation to the synchronized body
 *
 * As the type of synchronization does not rely on the reactive signaling by releasing processes, this methodology cannot support parking type of actions.
 * Instead, relying solely on yielding operations.
 * As this type is a sort of busy-wait, it can support up to 1200 concurrent Threads, at which point it MAY incur in
 * Lock-holder starvation due to cooperative yielding leading to runqueue inversion.
 *
 * Theoretically this synchronizer allows more throughput than the {@link FairSynchronizer} version which is strictly fair, and performs slightly better..
 * */
public class FastSynchronizer {

    AtomicInteger ticket = new AtomicInteger();
    AtomicInteger done = new AtomicInteger();
    int cur;

    int busy = FALSE;

    static final int
            FALSE = 0,
            TRUE = 1,
            NAN = 2;

    static final VarHandle BUSY;
    static final VarHandle CUR;

    static {
        try {
            BUSY = MethodHandles.lookup().findVarHandle(
                    FastSynchronizer.class, "busy",
            int.class);
            CUR = MethodHandles.lookup().findVarHandle(
                    FastSynchronizer.class, "cur",
            int.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static final int cores = - (Runtime.getRuntime().availableProcessors() / 2);
//    static final int cores = - Runtime.getRuntime().availableProcessors();

    //Test with spinwait
    public void acquire() {
        if (!BUSY.compareAndSet(this, FALSE, TRUE)) {
            int currentTicket = this.ticket.incrementAndGet();
            int d = -1;
            for (boolean yield = false;;) {
                int n_d = done.getOpaque();
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
            while (!BUSY.compareAndSet(this, FALSE, NAN)) {
                Thread.onSpinWait();
            }
            cur = currentTicket;
        }
    }

    public void release() {
        int prev = busy;
        BUSY.setRelease(this, FALSE);
        if (prev == NAN) {
            done.setRelease(cur);
        }
    }
}
