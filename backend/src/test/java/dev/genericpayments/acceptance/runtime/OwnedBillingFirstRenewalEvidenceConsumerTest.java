package dev.genericpayments.acceptance.runtime;

import java.io.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Pure fake process only: no child, application, database, socket or SDK. */
class OwnedBillingFirstRenewalEvidenceConsumerTest {
    private static void verify(Process process,Duration timeout) throws Exception {
        // Reflection keeps the behavior RED executable before the new helper exists.
        Class<?> helper=null;try {helper=Class.forName("dev.genericpayments.acceptance.runtime.OwnedBillingFirstRenewalEvidenceConsumer");}catch(ClassNotFoundException ignored){}
        assertThat(helper).as("bounded hosted consumer exists").isNotNull();
        try {helper.getDeclaredMethod("verify",byte[].class,Callable.class,Duration.class).invoke(null,"{}".getBytes(),(Callable<Process>)()->process,timeout);}
        catch(java.lang.reflect.InvocationTargetException failure){throw (Exception)failure.getCause();}
    }
    @Test void successfulFixedConsumerClosesStreamsAndUsesOnlyItsOutput() throws Exception {
        var process=new FakeProcess(false,false);verify(process,Duration.ofSeconds(1));assertThat(process.closed).isTrue();assertThat(process.input.toString()).isEqualTo("{}");
    }
    @Test void blockedStdinCannotEscapeOverallDeadlineOrPreventForcedCleanup() {
        var process=new FakeProcess(true,false);long began=System.nanoTime();
        assertThatThrownBy(()->verify(process,Duration.ofMillis(100))).hasMessage("COMBINED_EVIDENCE_REJECTED");
        assertThat(System.nanoTime()-began).isLessThan(Duration.ofSeconds(3).toNanos());assertThat(process.destroyed.getCount()).isZero();assertThat(process.closed).isTrue();
    }
    @Test void blockedStdoutCannotEscapeOverallDeadlineOrPreventForcedCleanup() {
        var process=new FakeProcess(false,true);long began=System.nanoTime();
        assertThatThrownBy(()->verify(process,Duration.ofMillis(100))).hasMessage("COMBINED_EVIDENCE_REJECTED");
        assertThat(System.nanoTime()-began).isLessThan(Duration.ofSeconds(3).toNanos());assertThat(process.destroyed.getCount()).isZero();assertThat(process.closed).isTrue();
    }
    static final class FakeProcess extends Process {
        final CountDownLatch destroyed=new CountDownLatch(1);final ByteArrayOutputStream input=new ByteArrayOutputStream();final boolean blockInput,blockOutput;volatile boolean closed;
        FakeProcess(boolean blockInput,boolean blockOutput){this.blockInput=blockInput;this.blockOutput=blockOutput;}
        public OutputStream getOutputStream(){return new OutputStream(){public void write(int b)throws IOException{if(blockInput)awaitDestroy();input.write(b);}public void close(){closed=true;}};}
        public InputStream getInputStream(){return blockOutput?new InputStream(){public int read()throws IOException{awaitDestroy();return -1;}public void close(){closed=true;}}:new ByteArrayInputStream("combined_actual_sql_and_signed_union_verified\n".getBytes());}
        public InputStream getErrorStream(){return InputStream.nullInputStream();}
        void awaitDestroy()throws IOException{try{destroyed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException();}}
        public int waitFor()throws InterruptedException{if(blockInput)destroyed.await();return 0;}
        public boolean waitFor(long value,TimeUnit unit)throws InterruptedException{return !blockInput||destroyed.await(value,unit);}
        public int exitValue(){return 0;}
        public void destroy(){destroyed.countDown();}
        public Process destroyForcibly(){destroy();return this;}
        public boolean isAlive(){return (blockInput||blockOutput)&&destroyed.getCount()!=0;}
    }
}
