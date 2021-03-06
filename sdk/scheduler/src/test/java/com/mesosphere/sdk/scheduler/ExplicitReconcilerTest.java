package com.mesosphere.sdk.scheduler;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.mesosphere.sdk.framework.Driver;

import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.testutils.SchedulerConfigTestUtils;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Tests for {@link ExplicitReconciler}.
 */
public class ExplicitReconcilerTest {

    private static final Protos.TaskStatus TASK_STATUS_1 = Protos.TaskStatus.newBuilder()
            .setTaskId(Protos.TaskID.newBuilder().setValue("task-1").build())
            .setState(Protos.TaskState.TASK_RUNNING)
            .build();
    private static final Protos.TaskStatus TASK_STATUS_2 = Protos.TaskStatus.newBuilder()
            .setTaskId(Protos.TaskID.newBuilder().setValue("task-2").build())
            .setState(Protos.TaskState.TASK_LOST)
            .build();
    private static final Collection<Protos.TaskStatus> TASK_STATUSES =
            Arrays.asList(TASK_STATUS_1, TASK_STATUS_2);
    private static final long DEFAULT_TIME_MS = 12345L;

    @Mock private SchedulerDriver mockDriver;
    @Mock private StateStore mockStateStore;
    @Captor private ArgumentCaptor<Collection<Protos.TaskStatus>> taskStatusCaptor;

    private TestReconciler reconciler;

    @Before
    public void beforeAll() {
        MockitoAnnotations.initMocks(this);
        Driver.setDriver(mockDriver);
        reconciler = new TestReconciler(
                mockStateStore, SchedulerConfigTestUtils.getTestSchedulerConfig(), DEFAULT_TIME_MS);
    }

    @Test
    public void testStartEmpty() throws Exception {
        assertTrue(reconciler.isReconciled());

        when(mockStateStore.fetchStatuses()).thenReturn(Collections.emptyList());
        reconciler.start();

        assertTrue(reconciler.isReconciled());
        assertEquals(0, reconciler.remaining().size());

        reconciler.reconcile();

        verifyZeroInteractions(mockDriver);
    }

    @Test
    public void testStart() throws Exception {
        assertTrue(reconciler.isReconciled());
        when(mockStateStore.fetchStatuses()).thenReturn(TASK_STATUSES);

        reconciler.start();

        assertFalse(reconciler.isReconciled());
        assertEquals(2, reconciler.remaining().size());
    }

    @Test
    public void testStartMultipleTimes() throws Exception {
        assertTrue(reconciler.isReconciled());
        assertEquals(0, reconciler.remaining().size());

        when(mockStateStore.fetchStatuses()).thenReturn(Collections.singletonList(TASK_STATUS_1));
        reconciler.start();

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());

        when(mockStateStore.fetchStatuses()).thenReturn(Collections.singletonList(TASK_STATUS_2));
        reconciler.start(); // append

        assertFalse(reconciler.isReconciled());
        assertEquals(2, reconciler.remaining().size());

        when(mockStateStore.fetchStatuses()).thenReturn(TASK_STATUSES);
        reconciler.start(); // merge

        assertFalse(reconciler.isReconciled());
        assertEquals(2, reconciler.remaining().size());
    }

    @Test
    public void testUpdatesBeforeReconcile() throws Exception {
        when(mockStateStore.fetchStatuses()).thenReturn(TASK_STATUSES);
        reconciler.start();

        // update() called before requested via reconcile():
        reconciler.update(TASK_STATUS_1);

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());
        assertEquals(TASK_STATUS_2.getTaskId().getValue(), reconciler.remaining().iterator().next());

        reconciler.update(TASK_STATUS_1); // no change

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());
        assertEquals(TASK_STATUS_2.getTaskId().getValue(), reconciler.remaining().iterator().next());

        reconciler.update(TASK_STATUS_2);

        assertTrue(reconciler.isReconciled());
        assertEquals(0, reconciler.remaining().size());

        reconciler.reconcile(); // no-op
        verifyNoMoreInteractions(mockDriver);
    }

    @Test
    public void testReconcileSequence() throws Exception {
        when(mockStateStore.fetchStatuses()).thenReturn(TASK_STATUSES);
        reconciler.start();

        reconciler.reconcile(); // first call to reconcileTasks: 2 values

        assertFalse(reconciler.isReconciled());
        assertEquals(2, reconciler.remaining().size());

        reconciler.update(TASK_STATUS_2);

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());
        assertEquals(TASK_STATUS_1.getTaskId().getValue(), reconciler.remaining().iterator().next());

        reconciler.update(TASK_STATUS_2); // no change

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());
        assertEquals(TASK_STATUS_1.getTaskId().getValue(), reconciler.remaining().iterator().next());

        // still have a task left, but the time is still the same so driver.reconcile is skipped:
        reconciler.reconcile(); // doesn't call reconcileTasks due to timer

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());
        assertEquals(TASK_STATUS_1.getTaskId().getValue(), reconciler.remaining().iterator().next());

        // bump time forward and try again:
        reconciler.setNowMs(DEFAULT_TIME_MS + 30000);
        reconciler.reconcile(); // second call to reconcileTasks: 1 values

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());
        assertEquals(TASK_STATUS_1.getTaskId().getValue(), reconciler.remaining().iterator().next());

        reconciler.update(TASK_STATUS_1);

        assertTrue(reconciler.isReconciled());
        assertEquals(0, reconciler.remaining().size());

        reconciler.reconcile(); // third call to reconcileTasks: 0 values (implicit)

        assertTrue(reconciler.isReconciled());
        assertEquals(0, reconciler.remaining().size());

        reconciler.reconcile(); // no-op

        // we need to validate all calls at once due to how mockito deals with Collection calls.
        // otherwise it incorrectly throws "TooManyActualInvocations"
        verify(mockDriver, times(2)).reconcileTasks(taskStatusCaptor.capture());
        List<Collection<Protos.TaskStatus>> allCalls = taskStatusCaptor.getAllValues();
        assertEquals(2, allCalls.size());
        assertEquals(2, allCalls.get(0).size()); // first call (two tasks left)
        assertEquals(1, allCalls.get(1).size()); // second call (one task left)
    }

    @Test
    public void testTaskLostToTaskRunningTransition() throws Exception {
        when(mockStateStore.fetchStatuses()).thenReturn(Collections.singletonList(TASK_STATUS_2));
        reconciler.start();

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());

        reconciler.reconcile();
        verify(mockDriver, times(1)).reconcileTasks(Collections.singletonList(TASK_STATUS_2));

        assertFalse(reconciler.isReconciled());
        assertEquals(1, reconciler.remaining().size());

        final Protos.TaskStatus updatedTaskStatus = Protos.TaskStatus.newBuilder(TASK_STATUS_2)
                .setState(Protos.TaskState.TASK_RUNNING)
                .build();

        reconciler.update(updatedTaskStatus);
        reconciler.reconcile();

        assertTrue(reconciler.isReconciled());
        assertEquals(0, reconciler.remaining().size());
    }

    /**
     * A Reconciler with adjustable 'now'
     */
    private static class TestReconciler extends ExplicitReconciler {
        private long nowMs;

        private TestReconciler(StateStore store, SchedulerConfig schedulerConfig, long nowMs) {
            super(store, Optional.empty(), schedulerConfig);
            setNowMs(nowMs);
        }

        private void setNowMs(long nowMs) {
            this.nowMs = nowMs;
        }

        @Override
        protected long getCurrentTimeMillis() {
            return nowMs;
        }
    }
}
