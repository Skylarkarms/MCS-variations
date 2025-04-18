import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.locks.LockSupport;

/**
 * This synchronizer ("FairMCS"), as opposed as {@link UnfairBusyMCS} or {@link UnfairMCS},
 * forces every {@link Thread} to enter the MCS linked queue.
 * <p> As is usual the MCS tradeoff of not using the CLH ({@link java.util.concurrent.locks.ReentrantReadWriteLock}) version is its increased memory allocation in exchange for flag locality and contention latency spread through {@link #TAIL} and `witness.next` during Node pushes.
 * */
public class FairMCS {
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
                    FairMCS.class, "top", Node.class
            );
            TAIL = MethodHandles.lookup().findVarHandle(
                    FairMCS.class, "tail", Node.class
            );
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }


    Node top = null;
    volatile Node tail = null;



    private Node bottomSet(Node nextNode) {
        Node wit;
        if ((wit = (Node) TAIL.compareAndExchange(this, null, nextNode)) == null) {
            top = nextNode;

            return null;
        } else return wit;
    }

    public void acquire() {
            Node h = tail;
            final Node nextNode = new Node();

            if (h != null || (h = bottomSet(nextNode)) != null) {
                cont:
                do {
                    do {
                        if (NEXT.compareAndSet(h, null, nextNode)) {
                            TAIL.compareAndSet(this, h, nextNode);
                            while (nextNode.parked) {
                                LockSupport.park();
                            }
                            break cont;
                        }
                        h = tail;
                    } while (h != null);
                    h = bottomSet(nextNode);
                } while (h != null);
            }
    }

    public void release() {
//        BUSY.setRelease(this, false);
        // -------- poll

        Node first = top;
        final Node next = (Node) NEXT.compareAndExchange(first, first.next, Node.removed);
        if (next == null) {
            // remove BOTTOM first
            if (TAIL.compareAndSet(this, first, null)) { // top will only be replaced sequentially,
                // UNLESS when being set to null, since new pushes occur asynchronously to this polling.
                TOP.compareAndSet(this, first, null);
            }
            else {
                Node trueNext = first.next;
                top = trueNext;

                if (PARKED.compareAndSet(trueNext, true, false)) {
                    LockSupport.unpark(trueNext.current);
                }
            }
        }
        else {
            top = next;
            if (PARKED.compareAndSet(next, true, false)) {
                LockSupport.unpark(next.current);
            }
        }
    }
}
