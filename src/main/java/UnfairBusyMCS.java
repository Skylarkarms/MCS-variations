import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * This synchronizer ("UnfairMCS"), as opposed to the standard {@link java.util.concurrent.locks.ReentrantLock}
 * contains a "fast-path" that allows winner to skip the queue.
 * This additional flag allowed us the delegation of the head swapping to the release phase, since the fast flag {@link #BUSY} needs to be set
 * Additionally, we can preemptively awake the new head during release phase, so that the context-recreation
 * is allowed to take place while the current process is still running.
 * <p> As is usual the MCS tradeoff of not using the CLH ({@link java.util.concurrent.locks.ReentrantReadWriteLock}) version is its increased memory allocation in exchange for flag locality and contention latency spread through {@link #TAIL} and `witness.next` during Node pushes.
 * */
public class UnfairBusyMCS {
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
    static final VarHandle BOTTOM;

    static {
        try {
            PARKED = MethodHandles.lookup().findVarHandle(
                    Node.class, "parked",
                    boolean.class);
            NEXT = MethodHandles.lookup().findVarHandle(
                    Node.class, "next", Node.class
            );
            TOP = MethodHandles.lookup().findVarHandle(
                    UnfairBusyMCS.class, "top", Node.class
            );
            BOTTOM = MethodHandles.lookup().findVarHandle(
                    UnfairBusyMCS.class, "bottom", Node.class
            );
            BUSY = MethodHandles.lookup().findVarHandle(
                    UnfairBusyMCS.class, "busy",
                    boolean.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }


    Node top = null;
    volatile Node bottom = null;


//    boolean busy = false;
    volatile boolean busy = false;
    static final VarHandle BUSY;

    private Node bottomSet(Node nextNode) {
        Node wit;
        if ((wit = (Node) BOTTOM.compareAndExchange(this, null, nextNode)) == null) {
            top = nextNode;
            return null;
        } else return wit;
    }

    public void acquire() {
        if (!BUSY.compareAndSet(this, false, true)
        ) {
            Node h = bottom;
            final Node nextNode = new Node();

            if (h != null || (h = bottomSet(nextNode)) != null) {
                cont:
                do {
                    do {
                        if (NEXT.compareAndSet(h, null, nextNode)) {
                            BOTTOM.compareAndSet(this, h, nextNode);
                            while (nextNode.parked) {
                                Thread.yield();
                            }
                            break cont;
                        }
                        h = bottom;
                    } while (h != null);
                    h = bottomSet(nextNode);
                } while (h != null);
            }

            // ------ set busy

            while (!BUSY.compareAndSet(this, false, true)) {
                Thread.onSpinWait();
            }


            // -------- poll

            Node first = top;
            final Node next = (Node) NEXT.compareAndExchange(first, first.next, Node.removed);
            if (next == null) {
                // remove BOTTOM first
                if (BOTTOM.compareAndSet(this, first, null)) { // top will only be replaced sequentially,
                    // UNLESS when being set to null, since new pushes occur asynchronously to this polling.
                    TOP.compareAndSet(this, first, null);
                }
                else {
                    Node trueNext = first.next;
                    top = trueNext;

                    PARKED.setRelease(trueNext, false);
//                    if (PARKED.compareAndSet(trueNext, true, false)) {
//                        LockSupport.unpark(trueNext.current);
//                    }
//                    top = first.next;
                }
            }
//            else top = next;
            else {
                top = next;
                PARKED.setRelease(next, false);
//                if (PARKED.compareAndSet(next, true, false)) {
//                    LockSupport.unpark(next.current);
//                }
            }

            // ------- end

        }
    }

    public void release() {
        BUSY.setRelease(this, false);
    }
}
