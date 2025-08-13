import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * In this MCS strategy, we offset latency by spreading it across multiple `node.next`, as opposed to CLH which focuses its CAS pressures on a single point of contention (TAIL).
 * The addition of a fast-path atomic flag (similar to {@link java.util.concurrent.locks.AbstractQueuedSynchronizer}’s `state`), also grants us an additional spin-locking point, granting us a premature release, offering an eager context-switch restoration preemption before the next node’s turn arrives.
 * This implementation of MCS, allows the HEAD to be semi-awake, busy-waiting on the release of the lock.
 * And once the lock is finally acquired, immediately waking up the next node, so that it has a chance to context-switch before the synchronized body sequence even finishes processing before releasing the lock.
 * My argument is that MCS’ type strategies are more efficient energy-wise since they allow a faster sleep (on 3rd places onwards inside the queue in my specific implementation), and a faster wake-up (as the HEAD will always stay awake busy-waiting).
 * The contention being spread across individual `node.next` references dissipates the latency contention, relieving it on unbounded node CAS’es, instead of a single focused CAS on TAIL.
 * @author Juan Andrade Salazar
 * <p> - juanandrade_20@hotmail.com
 * */
public class WeakUnfairMCS {
    private static class Node {

        public Node xchg(Node nextNode) { return (Node) next_acq.xchg(this, null, nextNode); }

        static final Node removed = new Node(new Node());

        public Node tryRemove(Node exp) { return (Node) next_acq.xchg(this, exp, removed); }

        public void setRemoved() { NEXT.set(this, removed); }

        final Thread current = Thread.currentThread();

        boolean parked = true;

        volatile Node next;

        public Node(Node next) { this.next = next; }

        public Node() { this.next = null; }

        void park() {
            while ((boolean) PARKED.getOpaque(this)) {
                LockSupport.park();
            }
        }
    }

    public record Functions() {
        public static final Function<WeakUnfairMCS, Runnable>
                partiallyFair = unfairMCS -> unfairMCS::acquire,
                unfair = unfairMCS -> unfairMCS::freeAcquire;

        public static Boolean isUnfair(Function<WeakUnfairMCS, Runnable> function) {
            if (function == unfair) return Boolean.TRUE;
            else if (function == partiallyFair) return Boolean.FALSE;
            else return null;
        }
    }

    private static final VarHandle NEXT;
    private static final VarHandle PARKED;
    private static final VarHandle TOP;
    private static final VarHandle TAIL;
    private static final VarHandle BUSY;
    private static final WeakOpt.CAX next_acq;
    private static final WeakOpt.CAS busy_acq;
    private static final WeakOpt.CMPXCHG tail_acq;
    private static final WeakOpt.CAX tail_plain;
    private static final WeakOpt.CAS top_plain;

    static {
        try {
            PARKED = MethodHandles.lookup().findVarHandle(Node.class, "parked", boolean.class);
            NEXT = MethodHandles.lookup().findVarHandle(Node.class, "next", Node.class);
            next_acq = WeakOpt.getCAX(NEXT, WeakOpt.FENCE.ACQ);
            TOP = MethodHandles.lookup().findVarHandle(WeakUnfairMCS.class, "top", Node.class);
            top_plain = WeakOpt.getCAS(TOP, WeakOpt.FENCE.PLAIN);
            TAIL = MethodHandles.lookup().findVarHandle(WeakUnfairMCS.class, "tail", Node.class);
            tail_acq = WeakOpt.getCMPXCHG(TAIL, WeakOpt.FENCE.ACQ);
            tail_plain = WeakOpt.getCAX(TAIL, WeakOpt.FENCE.PLAIN);
            BUSY = MethodHandles.lookup().findVarHandle(WeakUnfairMCS.class, "busy", boolean.class);
            busy_acq = WeakOpt.getCAS(BUSY, WeakOpt.FENCE.ACQ);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Node top = null;
    private volatile Node tail = null;

    private volatile boolean busy = false;

    private Object firstTail(Node nextNode) {
        Object wit;
        if ((wit = tail_acq.xchg(this, null, nextNode)) == null) {
            top = nextNode;
            return null;
        } else return wit;
    }

    boolean busy() { return busy; }

    public void whileBusy() {
        while (busy) {
            Thread.onSpinWait();
        }
    }

    /** Unfair busy spin-wait*/
    public void freeAcquire() {
        if (!busy_acq.cas(this, false, true)) {
            Thread.onSpinWait();
            while (!BUSY.compareAndSet(this, false, true)) { //|| ^^ && vv ||
                Thread.onSpinWait();
            }
        }
    }

    @SuppressWarnings("StatementWithEmptyBody")
    public void acquire() {
        Object h = tail;
        boolean nullH = h == null;
        if (nullH && busy_acq.cas(this, false, true)) return;
        final Node nextNode = new Node();
        cont:
        if (!nullH || (h = firstTail(nextNode)) != null) {
            Node next_n = (Node) h;
            for(;;) {
                next_n = next_n.xchg(nextNode);
                if (next_n == null) break;
                if (next_n.next == null) continue;
                h = tail;
                if (h == null) {
                    if (busy_acq.cas(this, false, true)) return;
                    h = firstTail(nextNode);
                    if (h == null) break cont;
                }
                next_n = (Node) h;
            }

            Object reg = tail_plain.xchg(this, h, nextNode);
            while (h != reg && nextNode.next == null) {
                h = reg;
                reg = tail_plain.xchg(this, h, nextNode);
            }

            nextNode.park();
        }

        while (!BUSY.compareAndSet(this, false, true)) { }

        // -------- poll

        final Node first = top; // contentionless fetch after a cas seq_const (BUSY) committing previous loads and writes
        final Node exp = first.next;
        Node next = first.tryRemove(exp);
        if (next != exp) {
            first.setRemoved();
            VarHandle.storeStoreFence();
        } else if (next == null) {
            if (tail_acq.cas(this, first, null)) {
                top_plain.cas(this, first, null);
                return;
            }
            next = first.next;
        }

        top = next;
        next.parked = false;
        VarHandle.storeStoreFence();
        LockSupport.unpark(next.current);
        // BUSY.set release keeps everything up here...
    }

    public void release() { BUSY.setRelease(this, false); }
}

