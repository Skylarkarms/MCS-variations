import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * In MCS’ strategies, we can offset latency by the spreading it across multiple `node.next`, as opposed to CLH which focuses its CAS pressures on a single point of contention (TAIL).
 * The addition of a `fast-path` atomic flag (similar to {@link java.util.concurrent.locks.AbstractQueuedSynchronizer}’s `state`), also grants us an additional spin-locking point, granting us a premature release, offering an eager context-switch restoration preemption before the next node’s turn arrives.
 * This implementation of MCS, allows the HEAD to be "semi-awake", busy-waiting on the release of the lock.
 * And once the lock is finally acquired, immediately waking up the next node, so that it has a chance to context-switch before the synchronized body sequence even finishes processing before releasing the lock.
 * My argument is that MCS’ type strategies are more efficient energy-wise since they allow a faster sleep (on 3rd places onwards inside the queue in my specific implementation), and a faster wake-up (as the HEAD will always stay awake busy-waiting).
 * The contention being spread across individual `node.next` references dissipates the latency contention, relieving it on unbounded node CAS’es, instead of a single focused CAS on TAIL.
 * */
public class UnfairMCS {
    private static class Node {

        final Thread current = Thread.currentThread();
        volatile boolean parked = true;

        volatile Node next;
        static final Node removed = new Node();

        @Override
        public String toString() {
            return "Node{" +
                    ", Thread=" + current +
                    ", next=" + (next == null ? "[null]" : next.hashCode()) +
                    "}@".concat(Integer.toString(hashCode()));
        }
    }

    static final VarHandle NEXT;
    static final VarHandle PARKED;

    static final VarHandle TOP;
    static final VarHandle TAIL;

    static {
        try {
            PARKED = MethodHandles.lookup().findVarHandle(
                    Node.class, "parked",
                    boolean.class);
            NEXT = MethodHandles.lookup().findVarHandle(
                    Node.class, "next", Node.class
            );
            TOP = MethodHandles.lookup().findVarHandle(
                    UnfairMCS.class, "top", Node.class
            );
            TAIL = MethodHandles.lookup().findVarHandle(
                    UnfairMCS.class, "tail", Node.class
            );
            FAST_PATH = MethodHandles.lookup().findVarHandle(
                    UnfairMCS.class, "busy",
                    boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }


    Node top = null;
    volatile Node tail = null;


    volatile boolean busy = false;
    static final VarHandle FAST_PATH;

    private Node bottomSet(Node nextNode) {
        Node wit;
        if ((wit = (Node) TAIL.compareAndExchange(this, null, nextNode)) == null) {
            top = nextNode;

            return null;
        } else return wit;
    }

    public void acquire() {
        if (!FAST_PATH.compareAndSet(this, false, true)
        ) {
            Node h = tail;
            final Node nextNode = new Node();

            if (h != null || (h = bottomSet(nextNode)) != null) {
                b_point:
                do {
                    do {
                        if (NEXT.compareAndSet(h, null, nextNode)) {
                            TAIL.compareAndSet(this, h, nextNode);
                            while (nextNode.parked) {
                                LockSupport.park();
                            }
                            break b_point;
                        }
                        h = tail;
                    } while (h != null);
                    h = bottomSet(nextNode);
                } while (h != null);
            }

            // ------ set busy

            while (!FAST_PATH.compareAndSet(this, false, true)) { // strong barrier
                Thread.onSpinWait();
            }


            // -------- poll

            Node first = top;
            final Node next = first.next;
            first.next = Node.removed;
            if (next == null) {
                if (TAIL.compareAndSet(this, first, null)) { // top will only be replaced sequentially,
                    // UNLESS when being set to null, since new pushes occur asynchronously to this polling.
                    TOP.compareAndSet(this, first, null);
                } else {
                    Node trueNext = first.next;
                    top = trueNext;
                    if (trueNext.parked) {
                        trueNext.parked = false;
                        LockSupport.unpark(trueNext.current);
                    }
                }
            } else {
                top = next;
                if (next.parked) {
                    next.parked = false;
                    LockSupport.unpark(next.current);
                }
            }


//            final Node next = (Node) NEXT.compareAndExchange(first, first.next, Node.removed);
//            if (next == null) {
//                // remove BOTTOM first
//                if (TAIL.compareAndSet(this, first, null)) { // top will only be replaced sequentially,
//                    // UNLESS when being set to null, since new pushes occur asynchronously to this polling.
//                    TOP.compareAndSet(this, first, null);
//                }
//                else {
//                    Node trueNext = first.next;
//                    top = trueNext;
//
//                    if (PARKED.compareAndSet(trueNext, true, false)) {
//                        LockSupport.unpark(trueNext.current);
//                    }
//                }
//            }
//            else {
//                top = next;
//                if (PARKED.compareAndSet(next, true, false)) {
//                    LockSupport.unpark(next.current);
//                }
//            }

            // ------- end

        }
    }

    public void release() { FAST_PATH.setRelease(this, false); }
}
