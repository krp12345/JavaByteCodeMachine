// FinalFieldExperiment.java
//
// Goal: observe the final-field "freeze" memory barrier (MemBarStoreStore /
// MemBarRelease) that the JIT injects at the end of a constructor so that a
// fully-initialized object is safely published.
//
// No warmup loop is used. Compilation is forced deterministically with:
//   -Xcomp -XX:CompileCommand=compileonly,FinalFieldExperiment::testMethod
// so testMethod is C2-compiled on its single first invocation.

class FinalFieldHolder {
    final int finalField;     // freeze must guarantee this is visible after publication
    int nonFinalField;        // no such guarantee
    int anotherNonFinal;
    public FinalFieldHolder() {
        this.nonFinalField = 200; // ordinary write
        this.finalField = 100;    // final write -> StoreStore freeze before object escapes
        this.anotherNonFinal = 568;
    }
}

public class FinalFieldExperiment {
    // 'static volatile' is avoided on purpose: we want the *final-field* barrier,
    // not a volatile-store barrier. Keep the publish target reachable so the
    // allocation + constructor are not dead-code eliminated.
    public static FinalFieldHolder holder;

    // The method we instruct C2 to compile and print.
    public static void testMethod() {
        holder = new FinalFieldHolder();
    }

    public static void main(String[] args) {
        // Single call. Under -Xcomp this triggers immediate C2 compilation;
        // no hot-loop tuning required.
        testMethod();
        // Consume the result so nothing is optimized away.
        System.out.println("finalField=" + holder.finalField
                + " nonFinalField=" + holder.nonFinalField);
    }
}
