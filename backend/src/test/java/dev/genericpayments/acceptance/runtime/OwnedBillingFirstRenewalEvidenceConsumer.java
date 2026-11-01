package dev.genericpayments.acceptance.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Only the fixed hosted evidence consumer; absent from production and exported runtime. */
final class OwnedBillingFirstRenewalEvidenceConsumer {
    private static final String EXPECTED="combined_actual_sql_and_signed_union_verified\n";
    private OwnedBillingFirstRenewalEvidenceConsumer() {}
    static void verify(byte[] input,Callable<Process> start,Duration budget) {
        if(input.length>262144||budget.isNegative()||budget.isZero()||budget.compareTo(Duration.ofSeconds(10))>0)throw rejected();
        long deadline=System.nanoTime()+budget.toNanos();var selected=new AtomicReference<Process>();var expired=new AtomicBoolean();
        var work=new FutureTask<Void>(()->{
            var process=start.call();selected.set(process);
            // Startup can return after caller cancellation; never leave that late child alive.
            if(expired.get()){cleanup(process);throw rejected();}
            try(var stdin=process.getOutputStream()){stdin.write(input);}
            if(!process.waitFor(remaining(deadline),TimeUnit.NANOSECONDS)||process.exitValue()!=0)throw rejected();
            var output=new String(process.getInputStream().readNBytes(256),StandardCharsets.UTF_8);
            if(!EXPECTED.equals(output)||remaining(deadline)<=0)throw rejected();
            return null;
        });
        Thread.ofVirtual().name("combined-evidence-consumer").start(work);
        try {work.get(remaining(deadline),TimeUnit.NANOSECONDS);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw rejected();}
        catch(Exception failure){throw rejected();}
        finally {expired.set(true);work.cancel(true);var process=selected.get();if(process!=null)cleanup(process);}
    }
    private static long remaining(long deadline){long left=deadline-System.nanoTime();if(left<=0)throw rejected();return left;}
    private static IllegalStateException rejected(){return new IllegalStateException("COMBINED_EVIDENCE_REJECTED");}
    private static void cleanup(Process process) {
        // Killing precedes pipe close so a writer blocked in a full pipe can terminate.
        var cleanup=new FutureTask<Void>(()->{
            if(process.isAlive())process.destroyForcibly();
            if(!process.waitFor(5,TimeUnit.SECONDS))throw rejected();
            process.getOutputStream().close();process.getInputStream().close();process.getErrorStream().close();return null;
        });
        Thread.ofVirtual().name("combined-evidence-cleanup").start(cleanup);
        try {cleanup.get(5,TimeUnit.SECONDS);}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw rejected();}
        catch(Exception failure){throw rejected();}
        finally {cleanup.cancel(true);}
    }
}
