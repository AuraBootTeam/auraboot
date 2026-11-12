package com.auraboot.framework.notification.sourcebinding;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Bounded, identity-free progress admission; does not prove actual source execution. */
final class RegisteredSourceProgressTest {
    private RegisteredSourcePollingAdapter adapter() {
        return new RegisteredSourcePollingAdapter(new HostSourceBindingProperties(),
                mock(RegisteredSourcePlanResolver.class),mock(RegisteredSourceWorker.class));
    }
    private RegisteredSourcePollingAdapter.TickResult result(RegisteredSourcePollingAdapter.Status status,int selected) {
        return new RegisteredSourcePollingAdapter.TickResult(status,2,1,selected,selected,List.of());
    }
    @Test void onlySuccessfulNonemptyTicksProduceAtMostOneSummaryPerMinute() {
        var adapter=adapter();long minute=TimeUnit.MINUTES.toNanos(1);
        for(var status:List.of(RegisteredSourcePollingAdapter.Status.DISABLED,RegisteredSourcePollingAdapter.Status.BUSY,
                RegisteredSourcePollingAdapter.Status.FAILED))assertFalse(adapter.shouldReportProgress(result(status,1),0));
        assertFalse(adapter.shouldReportProgress(result(RegisteredSourcePollingAdapter.Status.SUCCEEDED,0),0));
        var progress=result(RegisteredSourcePollingAdapter.Status.SUCCEEDED,1);
        assertTrue(adapter.shouldReportProgress(progress,0));assertFalse(adapter.shouldReportProgress(progress,minute-1));
        assertTrue(adapter.shouldReportProgress(progress,minute));assertFalse(adapter.shouldReportProgress(progress,minute));
    }
    @Test void monotonicClockWrapDoesNotDisableProgressForever() {
        var adapter=adapter();var progress=result(RegisteredSourcePollingAdapter.Status.SUCCEEDED,1);
        long start=Long.MAX_VALUE-TimeUnit.SECONDS.toNanos(10);
        assertTrue(adapter.shouldReportProgress(progress,start));assertFalse(adapter.shouldReportProgress(progress,start+1));
        assertTrue(adapter.shouldReportProgress(progress,start+TimeUnit.MINUTES.toNanos(1)));
    }
    @Test void partialChannelSummariesShareTheSameBoundedRateAndRejectEmptyTicks() {
        var adapter=adapter();long minute=TimeUnit.MINUTES.toNanos(1);
        assertFalse(adapter.shouldReportProgress(result(RegisteredSourcePollingAdapter.Status.PARTIAL,0),0));
        assertTrue(adapter.shouldReportProgress(result(RegisteredSourcePollingAdapter.Status.PARTIAL,1),0));
        assertFalse(adapter.shouldReportProgress(result(RegisteredSourcePollingAdapter.Status.PARTIAL,1),minute-1));
        assertFalse(adapter.shouldReportProgress(result(RegisteredSourcePollingAdapter.Status.SUCCEEDED,1),minute-1));
        assertTrue(adapter.shouldReportProgress(result(RegisteredSourcePollingAdapter.Status.SUCCEEDED,1),minute));
    }
    @Test void concurrentObservationsProduceOneSummary() throws Exception {
        var adapter=adapter();var progress=result(RegisteredSourcePollingAdapter.Status.SUCCEEDED,1);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var calls=new ArrayList<Callable<Boolean>>();for(int i=0;i<32;i++)calls.add(()->adapter.shouldReportProgress(progress,123));
            int admitted=0;for(var future:executor.invokeAll(calls))if(future.get())admitted++;
            assertEquals(1,admitted);
        }
    }
}
