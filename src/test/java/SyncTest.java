import com.skylarkarms.print.Print;
import com.skylarkarms.stringutils.StringUtils;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SyncTest {

    private static final String TYPE = "weak_A";
//    private static final String TYPE = "weak_B";

    private static final String TAG = "MonitorTest_";

    static int instance_count;

    static void save() {
        if (instance_count < main_reps - 1) {
            instance_count++;
            main(null);
        } else {
            Print.cyan.ln("DONE...");
            String[][] res =
                    addRow(
                            StringUtils.fromColumns(SyncTest.times),
                            0, TYPE
                    )
                    ;
            Print.Export.to_csv.save(
                    "C:\\java_tests", TAG.concat(TYPE),
                    res
            );
        }
    }

    static final double factor = 1.5;
    static final int maxTries = 23;
//    static final int maxTries = 13;
//    static final int maxTries = 11;
//    static final int maxTries = 20;
//    static final int maxTries = 6;
//    static final int maxTries = 10;
//    static final int maxTries = 10;
//    static final int maxTries = 25;
//    static final int size = 50;
    static final int size = 23;
//    static final int size = 10;
//    static final int size = 500;
//    static final int size = 150_000;
//    static final int size = 700_000;
    static int main_reps = 8;
    static final long[][] times = new long[main_reps][maxTries];

    static class Adder {
        volatile int res = 0;
        private volatile BigInteger lastValue = BigInteger.valueOf(4);

        final WeakUnfairMCS monitor = new WeakUnfairMCS();//12
//        final WeakUnfairMCS_MHC7 monitor = new WeakUnfairMCS_MHC7();//15
//        final WeakUnfairMCS_AKHV monitor = new WeakUnfairMCS_AKHV();//18

//        synchronized void add(int i) {
        void add(int i) {
//            monitor.lock();
            monitor.acquire();
            res = res + i;
            lastValue = lastValue.multiply(BigInteger.valueOf(i));
//            monitor.unlock();
            monitor.release();
        }

//        int branch() {
//            return monitor.a_min_b();
//        }

        void sanity(int[] ints) {
            BigInteger lastValue = BigInteger.valueOf(4);
            int l_res = 0;
            for (int anInt : ints) {
                l_res += anInt;
                lastValue = lastValue.multiply(BigInteger.valueOf(anInt));
            }
            assert res == l_res :
                    "\n expected int = " + l_res
                    + "\n real = " + res;
            assert Objects.equals(this.lastValue, lastValue) : ""
                    + "\n Expected BigInt = " + lastValue
                    + "\n Real = " + this.lastValue
                    ;
        }
    }

    private static final Thread.UncaughtExceptionHandler handler = (t, e) -> {
        e.printStackTrace();
        System.exit(0);
    };

    public static void main(String[] args) {
        Print.yellow.ln("Begin... instance count = " + instance_count);
        next(0, size);
    }

    static void next(int tries, int size) {
        Print.green.ln("" +
                "\n Iteration = " + tries
                + "\n size = " + size
        );
        Executor service = command -> {
            Thread t = new Thread(command);
            t.setUncaughtExceptionHandler(handler);
            t.start();
        };
        Random r = new Random();
        int[] nums = r.ints(size, 10, 101).toArray();

        Adder adder = new Adder();
        long[] start = new long[1];
        AtomicInteger start_count = new AtomicInteger();
        AtomicInteger end_count = new AtomicInteger();
        for (int j = 0; j < size; j++) {
            int finalJ = j;
            service.execute(
                    () -> {
                        if (start_count.incrementAndGet() == 1) start[0] = System.nanoTime();

                        adder.add(nums[finalJ]);

                        // because of possible unfairness, we cannot rely on the BEFORE value from `start_count` and need a separate counter for the HAPPENS AFTER.
//                        if (start_count.decrementAndGet() == 0) {
                        if (end_count.incrementAndGet() == size) {
                            long p_l = System.nanoTime() - start[0];
                            long last = p_l/100;
                            try {
                                times[instance_count][tries] = last;
                                Print.blue.ln(
                                        "finish = " + Print.Nanos.toString(times[instance_count][tries])
//                                        + "\n branch count = " + adder.branch()
                                );
//                                adder.monitor.readBranch();
                                Thread.sleep(TimeUnit.MILLISECONDS.toMillis(150));
                                adder.sanity(nums);
                                int next_size = (int) (size * factor);
                                int next_try = tries + 1;
                                if (next_try < maxTries) {
                                    next(next_try, next_size);
                                } else {
                                    save();
                                }
                            } catch (Exception | Error e) {
                                throw new RuntimeException(e);
                            }
                        }
                    }
            );
        }
    }

    @SafeVarargs
    public static <T> T[][] addRow(T[][] table, int index, T... row) {
        if (table == null || row == null) {
            throw new IllegalArgumentException("Table and row cannot be null");
        }

        if (index < 0 || index > table.length) {
            throw new IllegalArgumentException("Index out of bounds");
        }

        @SuppressWarnings("unchecked")
        T[][] newTable = (T[][])
                Array.newInstance(table.getClass().getComponentType(), table.length + 1);

        System.arraycopy(table, 0, newTable, 0, index);
        newTable[index] = row.clone();
        System.arraycopy(table, index, newTable, index + 1, table.length - index);

        return newTable;
    }
}
