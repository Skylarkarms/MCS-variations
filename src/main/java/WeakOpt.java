import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/**
 * Solves LL/SC's spurious failure by retrying on `expected == vh.getOpaque()`, and returning:
 * <ul>
 *     <li>`false` on failure, `true` on success; in cas-like events</li>
 *     <li> `witness` on failure, `expected` on success; in exchange-like events</li>
 * </ul>
 * <pre>{@code
 *             static Object weakOptimizationXchg(CAS weak_strat, VarHandle vh, Object context, Object exp, Object set) {
 *             if (!weak_strat.cas(vh, context, exp, set)) {
 *                 if (exp == (exp = vh.getOpaque(context))) {
 *                     do {
 *                         if (weak_strat.cas(vh, context, exp, set)) return exp;
 *                     } while (
 *                             exp == (exp = vh.getOpaque(context))
 *                     );
 *                 }
 *             }
 *             return exp;
 *         }
 * }</pre>
 * This retry loop is removed on Total Store Ordered architectures, using their analogous strong versions instead.
 * <pre>{@code
 * CAS = VarHandle.compareAndSet
 * XCHG = VarHandle.compareAndExchange
 * EXP = expected
 *
 *                         WEAK                     |             STRONG
 *                CAS       |         XCHG          |        CAS         |     XCHG
 * PLAIN      weakCASPlain  |  weakCASPlain -> EXP  | XCHGAcquire == EXP |  XCHGAcquire
 * SEQ_CONST    weakCAS     |    weakCAS -> EXP     |        CAS         |     XCHG
 * ACQ       weakCASAcquire | weakCASAcquire -> EXP |    XCHGAcquire     |  XCHGAcquire
 * REL       weakCASRelease | weakCASRelease -> EXP |    XCHGRelease     |  XCHGRelease
 * }</pre>
 * Each of the weak versions will remain faithful to their memory ordering categorizations... UNLESS they become the target of a failed CAS.
 * <p> Upon failure, regardless of its cause (spurious or not)... the weak cas will now default to being an `opaque` fence...
 * <p> UNLESS the weak cas is done inside a spinlock, allowing only successful events to unlock it... the
 * {@code `jump`} instruction from its `loop` will anchor it, simply because a `false` speculative scenario... only goes back to the loop itself...
 * <p></>not allowing the speculative window to ever see what lies AFTER/BELLOW the loop.
 * <p> {@code `OOTA`} results are impossible since speculation only commits upon confirmation... so, retroactive miss-prediction reorderings will never commit.
 * <p> This would result in 2-4 different Processor-compiled sequences during {@code `speculation`},
 * two(2) where the original fence appears and another two(2) with an `acquire` fence added.
 * <p> This seq_const fence would only be applied on loads and stores <b>subsequent</b> to the failure event.
 * <p> Without this class... redundant checks with {@link VarHandle#getOpaque(Object...)} would never be optimized away in TSO architectures.
 * So, this implementation fixes that. The JIT-compiler is only left with the task of optimizing indirect accesses away via:
 *
 * <pre>{@code
 * Optimization Type  | Optimization                  | Role
 * -------------------| ----------------------------- | ---------------------------------------
 * Language-specific  | De-reflection                 | Removes indirect field access
 * Language-specific  | Symbolic constant propagation | Treats `static final` field as constant
 * Global code shaping| Inlining(graph integration)   | Inlines the call
 * Flow-sensitive     | Dead code elimination         | Removes now-unused field access
 * }</pre>
 *
 * Due to compiler <i>reasons</i>, the VarHandle methods have not been converted to method reference,
 * but this does not seem to affect JIT compiling optimizations.
 * @author Juan Andrade Salazar
 * <p> - juanandrade_20@hotmail.com
 * */
public class WeakOpt {

    final VarHandle vh;

    private static final String TAG = "WeakVarHandle";
    private static String[] processors = new String[]{
            "arm", "aarch64",
            "ppc", "ppc64", "power", "powerpc", "powerpc64",
            "riscv64", "sparc", "sparcv9", "mips", "mips64", "loongarch64"
    };

    public WeakOpt(VarHandle vh) { this.vh = vh; }

    public static synchronized void addWeak(String... processors) {
        alreadyInitializedExcpetion();
        int newL = processors.length;
        if (newL == 0) return;
        String[] prev = WeakOpt.processors;
        int prevL = processors.length;
        int all = prevL + newL;
        final String[] newProcs = new String[all];
        System.arraycopy(prev, 0, newProcs, 0, all);
        System.arraycopy(processors, 0, newProcs, prevL + 1, all);
        WeakOpt.processors = newProcs;
    }

    static volatile boolean inferred = false;

    private static void alreadyInitializedExcpetion() {
        if (inferred) throw new IllegalStateException(TAG.concat(" - Optimization already initialized."));
    }

    static String path = "os.arch";

    static Boolean weak = null;

    /**
     * Will only apply if none of the processors match the current architecture.
     * */
    public static synchronized void setWeak(boolean weak) {
        alreadyInitializedExcpetion();
        WeakOpt.weak = weak;
    }

    static class cas_ {
        boolean cas(VarHandle vh, Object context, Object exp, Object set) { return false;}
    }

    static class xchg_ {
        Object xchg(VarHandle vh, Object context, Object exp, Object set) { return null; }
    }

    static class int_xchg {
        int xchg(AtomicInteger ai, int exp, int set) { return 0;}
    }

    public enum FENCE {
        ACQ(
                () -> Arch.AcqCAS.ref,
                () -> Arch.AcqXCHG.ref
        ),
        REL(
                () -> Arch.RelCAS.ref,
                () -> Arch.RelXCHG.ref
        ),
        ACQ_REL(
                () -> Arch.SeqConstCAS.ref,
                () -> Arch.SeqConstXCHG.ref
        ),
        PLAIN(
                () -> Arch.PlainCAS.ref,
                () -> Arch.PlainXCHG.ref
        )
        ;
        final Supplier<cas_> cas;
        final Supplier<xchg_> cax;

        FENCE(Supplier<cas_> cas, Supplier<xchg_> cax) {
            this.cas = cas;
            this.cax = cax;
        }
    }

    // Leaving the singleton stored as a final field... is faster than the static reference in the "InterfaceSingleton" interface pattern...
    // One possibility is that... in the interface-singleton case... each initialization... enters a synchronized transaction... within the constructor fencing. (freezing).
    // The issue is NOT the synchronization per-se... since that will only occur once...
    // The issue is that the synchronization injects additional fencing between the constant creation and the Object creation... anchoring BOTH.
    // So.. in the final-field case... the lazily built constant is allowed to occur OUTSIDE the Object creation... so a speculative execution can be done
    // on the value of constant while the Object is still being created and even used...
    private enum Arch {;
        static final boolean isWeak = isWeak();
        static boolean isWeak() {
            inferred = true;
            String arch = System.getProperty(path).toLowerCase();
            System.out.println(TAG.concat(" - Architecture = " + arch
                    + "\n    at path = " + path
            ));
            if (arch.equals("x86") || arch.equals("amd64")) {
                System.out.println(TAG.concat(" - Strong memory ordered processor found [" + arch + "], weak CASes not applied."));
                return false;
            }
            for (String procs:processors
            ) {
                if (arch.contains(procs.toLowerCase())) {
                    System.out.println(TAG.concat("Weak model found [" + procs + "] = true"));
                    return true;
                }
            }
            Boolean l_w = weak;
            if (l_w != null) {
                System.out.println(TAG.concat(" - Explicit set to `weak` = " + l_w));
                return l_w;
            }
            System.out.println(TAG.concat(" - No weak model found from \n Options = \n " + Arrays.toString(processors)
                    + "\n    add more Options with `#add(String... processors)`"
                    + "\n    Or set explicit `true` with `#setWeak(boolean)`"
            ));
            return false;
        }

        enum AcqCAS {;
            static final cas_ ref = isWeak ?
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSetAcquire(context, exp, set)) {
                                if (exp == vh1.getOpaque(context)) {
                                    do {
                                        if (vh1.weakCompareAndSetAcquire(context, exp, set)) return true;
                                    } while (
                                            exp == vh1.getOpaque(context)
                                    );
                                }
                                return false;
                            } else return true;
                        }
                    }
                    :
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndExchangeAcquire(context, exp, set) == exp;
                        }
                    };
        }
        enum RelCAS {;
            static final cas_ ref = isWeak ?
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSetRelease(context, exp, set)) {
                                if (exp == vh1.getOpaque(context)) {
                                    do {
                                        if (vh1.weakCompareAndSetRelease(context, exp, set)) return true;
                                    } while (
                                            exp == vh1.getOpaque(context)
                                    );
                                }
                                return false;
                            } else return true;
                        }
                    }
                    :
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndExchangeRelease(context, exp, set) == exp;
                        }
                    };
        }
        enum PlainCAS {;
            static final cas_ ref = isWeak ?
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSetPlain(context, exp, set)) {
                                if (exp == vh1.getOpaque(context)) {
                                    do {
                                        if (vh1.weakCompareAndSetPlain(context, exp, set)) return true;
                                    } while (
                                            exp == vh1.getOpaque(context)
                                    );
                                }
                                return false;
                            } else return true;
                        }
                    }
                    :
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndExchangeAcquire(context, exp, set) == exp;
                        }
                    };
        }
        enum SeqConstCAS {;
            static final cas_ ref = isWeak ?
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSet(context, exp, set)) {
                                if (exp == vh1.getOpaque(context)) {
                                    do {
                                        if (vh1.weakCompareAndSet(context, exp, set)) return true;
                                    } while (
                                            exp == vh1.getOpaque(context)
                                    );
                                }
                                return false;
                            } else return true;
                        }
                    }
                    :
                    new cas_() {
                        @Override
                        public boolean cas(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndSet(context, exp, set);
                        }
                    };
        }
        enum AcqXCHG {;
            static final xchg_ ref = isWeak ?
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSetAcquire(context, exp, set)) {
                                if (exp == (exp = vh1.getOpaque(context))) {
                                    do {
                                        if (vh1.weakCompareAndSetAcquire(context, exp, set)) return exp;
                                    } while (
                                            exp == (exp = vh1.getOpaque(context))
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndExchangeAcquire(context, exp, set);
                        }
                    };
        }
        enum SeqConstXCHG {;
            static final xchg_ ref = isWeak ?
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSet(context, exp, set)) {
                                if (exp == (exp = vh1.getOpaque(context))) {
                                    do {
                                        if (vh1.weakCompareAndSet(context, exp, set)) return exp;
                                    } while (
                                            exp == (exp = vh1.getOpaque(context))
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndExchange(context, exp, set);
                        }
                    };
        }
        enum RelXCHG {;
            static final xchg_ ref = isWeak ?
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSetRelease(context, exp, set)) {
                                if (exp == (exp = vh1.getOpaque(context))) {
                                    do {
                                        if (vh1.weakCompareAndSetRelease(context, exp, set)) return exp;
                                    } while (
                                            exp == (exp = vh1.getOpaque(context))
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndExchangeRelease(context, exp, set);
                        }
                    };
        }
        enum PlainXCHG {;
            static final xchg_ ref = isWeak ?
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            if (!vh1.weakCompareAndSetPlain(context, exp, set)) {
                                if (exp == (exp = vh1.getOpaque(context))) {
                                    do {
                                        if (vh1.weakCompareAndSetPlain(context, exp, set)) return exp;
                                    } while (
                                            exp == (exp = vh1.getOpaque(context))
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new xchg_() {
                        @Override
                        public Object xchg(VarHandle vh1, Object context, Object exp, Object set) {
                            return vh1.compareAndExchangeAcquire(context, exp, set);
                        }
                    };
        }

        //--------------- AtomciInteger ---------------//

        enum INTAcqXCHG {;
            static final int_xchg ref = isWeak ?
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger ai, int exp, int set) {
                            if (!ai.weakCompareAndSetAcquire(exp, set)) {
                                if (exp == (exp = ai.getOpaque())) {
                                    do {
                                        if (ai.weakCompareAndSetAcquire(exp, set)) return exp;
                                    } while (
                                            exp == (exp = ai.getOpaque())
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger atomicInteger, int expectedValue, int newValue) {
                            return atomicInteger.compareAndExchangeAcquire(expectedValue, newValue);
                        }
                    };
        }
        enum INTAcqRelXCHG {;
            static final int_xchg ref = isWeak ?
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger ai, int exp, int set) {
                            if (!ai.weakCompareAndSetAcquire(exp, set)) {
                                if (exp == (exp = ai.getOpaque())) {
                                    do {
                                        if (ai.weakCompareAndSetAcquire(exp, set)) return exp;
                                    } while (
                                            exp == (exp = ai.getOpaque())
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger atomicInteger, int expectedValue, int newValue) {
                            return atomicInteger.compareAndExchangeAcquire(expectedValue, newValue);
                        }
                    };
        }
        enum INTRelXCHG {;
            static final int_xchg ref = isWeak ?
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger ai, int exp, int set) {
                            if (!ai.weakCompareAndSetRelease(exp, set)) {
                                if (exp == (exp = ai.getOpaque())) {
                                    do {
                                        if (ai.weakCompareAndSetRelease(exp, set)) return exp;
                                    } while (
                                            exp == (exp = ai.getOpaque())
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger atomicInteger, int expectedValue, int newValue) {
                            return atomicInteger.compareAndExchangeRelease(expectedValue, newValue);
                        }
                    };
        }
        enum INTPlainXCHG {;
            static final int_xchg ref = isWeak ?
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger ai, int exp, int set) {
                            if (!ai.weakCompareAndSetPlain(exp, set)) {
                                if (exp == (exp = ai.getOpaque())) {
                                    do {
                                        if (ai.weakCompareAndSetPlain(exp, set)) return exp;
                                    } while (
                                            exp == (exp = ai.getOpaque())
                                    );
                                }
                            }
                            return exp;
                        }
                    }
                    :
                    new int_xchg() {
                        @Override
                        public int xchg(AtomicInteger atomicInteger, int expectedValue, int newValue) {
                            return atomicInteger.compareAndExchangeAcquire(expectedValue, newValue);
                        }
                    };
        }
    }

    // Moves the lazy allocation of the field OUTSIDE the constructor...
    // Improving performance by:
    // Allowing free speculation of function/enum value BEFORE object creation.
    // In return the processor is allowed to speculate the creation and run of Threads before this object has finished creating.
    // NO OOTA(Out of thin air) values are possible on real hardware since speculation occurs privately in the Thread cache
    // and will never commit UNTIL confirmation returns true.
    // Leaving the enum to lazy-init inside the constructor.. creates a strong dependence (fences between fences) so no speculation is allowed in the middle of the process between enum initialization and `WeakOptimizer` creation.
    public static CAS getCAS(VarHandle vh, FENCE fence) { return new CAS(vh, fence.cas.get()); }
    public static CAX getCAX(VarHandle vh, FENCE fence) { return new CAX(vh, fence.cax.get()); }
    public static CMPXCHG getCMPXCHG(VarHandle vh, FENCE fence) { return new CMPXCHG(vh,fence.cas.get() , fence.cax.get()); }

    // VarHandle inheritance has shown better performance than direct referencing during C2 compilation.
    // Explanation: Each loading of `vh` refers to the same field (method) on all children usages... hence "hitting" the execution-counter with more hits.
    // Fostering a faster memory layout optimization and faster inlining.
    // The shared scope... allows the optimization to "trickle-down" to all children via OSR using the machine code stored on the underlying `shared_runtime`.
    public static final class CAS extends WeakOpt {
        private final cas_ c;
        private CAS(VarHandle vh, cas_ c) {
            super(vh);
            this.c = c;
        }
        public boolean cas(Object t, Object e, Object s) { return c.cas(vh, t, e, s); }
    }

    public static final class CAX extends WeakOpt {
        private final xchg_ x;
        private CAX(VarHandle vh, xchg_ x) {
            super(vh);
            this.x = x;
        }
        public Object xchg(Object t, Object e, Object s) { return x.xchg(vh, t, e, s); }
    }

    public static final class CMPXCHG extends WeakOpt {
        private final cas_ c;
        private final xchg_ x;
        private CMPXCHG(VarHandle vh,
                       cas_ c, xchg_ x
            ) {
            super(vh);
            this.c = c;
            this.x = x;
        }
        public boolean cas(Object t, Object e, Object s) { return c.cas(vh, t, e, s); }
        public Object xchg(Object t, Object e, Object s) { return x.xchg(vh, t, e, s); }
    }

    public static final class WeakAtomicInteger {
        final AtomicInteger ai;
        final int_xchg x;

        public enum FENCE {
            ACQ(
                    () -> Arch.INTAcqXCHG.ref
            ),
            REL(
                    () -> Arch.INTRelXCHG.ref
            ),
            ACQ_REL(
                    () -> Arch.INTAcqRelXCHG.ref
            ),
            PLAIN(
                    () -> Arch.INTPlainXCHG.ref
            )
            ;
            final Supplier<int_xchg> cax;

            FENCE(Supplier<int_xchg> cax) { this.cax = cax; }
        }

        public static WeakAtomicInteger getInstance(AtomicInteger ai, FENCE fence) {
            return new WeakAtomicInteger(ai, fence.cax.get());
        }

        private WeakAtomicInteger(AtomicInteger ai, int_xchg x) {
            this.ai = ai;
            this.x = x;
        }

        public int decrementAndGet() {
            int prev = ai.getOpaque(), next;
            do {
                next = prev - 1;
            } while (prev != (prev = x.xchg(ai, prev, next)));
            return next;
        }

        public int updateAndGet(IntUnaryOperator operator) {
            int prev = ai.getOpaque(), next;
            do {
                next = operator.applyAsInt(prev);
            } while (prev != (prev = x.xchg(ai, prev, next)));
            return next;
        }

        public int incrementAndGet() {
            int prev = ai.getOpaque(), next;
            do {
                next = prev + 1;
            } while (prev != (prev = x.xchg(ai, prev, next)));
            return next;
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public int getAndDecrement() {
            int prev = ai.getOpaque();
            while (prev != (prev = x.xchg(ai, prev, prev - 1))) { }
            return prev;
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public int getAndUpdate(IntUnaryOperator operator) {
            int prev = ai.getOpaque();
            while (prev != (prev = x.xchg(ai, prev, operator.applyAsInt(prev)))) { }
            return prev;
        }

        @SuppressWarnings("StatementWithEmptyBody")
        public int getAndIncrement() {
            int prev = ai.getOpaque();
            while (prev != (prev = x.xchg(ai, prev, prev + 1))) { }
            return prev;
        }

        public void decrement() {
            int prev = ai.getOpaque();
            for(;;) {
                if (prev == (prev = x.xchg(ai, prev, prev - 1))) break;
            }
        }

        public void increment() {
            int prev = ai.getOpaque();
            for(;;) {
                if (prev == (prev = x.xchg(ai, prev, prev + 1))) break;
            }
        }

        public boolean cas(int exp, int set) { return x.xchg(ai, exp, set) == exp; }

        public int xchg(int exp, int set) { return x.xchg(ai, exp, set); }
    }
}
