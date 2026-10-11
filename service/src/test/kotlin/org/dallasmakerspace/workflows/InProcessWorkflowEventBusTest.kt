package org.dallasmakerspace.workflows

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.dallasmakerspace.models.workflows.WorkflowStreamMessage
import org.dallasmakerspace.models.workflows.WorkflowSubject
import org.junit.Test

class InProcessWorkflowEventBusTest {

  private val group = WorkflowSubject("group", "Test Group")

  /** Subscribes (synchronously), runs [publish], and returns the first [count] messages. */
  private fun collect(
      bus: WorkflowEventBus,
      filter: StreamFilter,
      count: Int,
      publish: () -> Unit,
  ): List<WorkflowStreamMessage> = runBlocking {
    val job =
        async(start = CoroutineStart.UNDISPATCHED) {
          withTimeout(2_000) { bus.subscribe(filter).take(count).toList() }
        }
    publish()
    job.await()
  }

  @Test
  fun `runId filter gets that run and its events only`() {
    val bus = InProcessWorkflowEventBus()
    val got =
        collect(bus, StreamFilter(runId = 5), 2) {
          bus.publishRun(testRun(4))
          bus.publishEvent(testEvent(1, runId = 4))
          bus.publishRun(testRun(5))
          bus.publishEvent(testEvent(2, runId = 5))
        }
    assertEquals(listOf("run", "event"), got.map { it.type })
    assertEquals(5, got[0].run?.id)
    assertEquals(2, got[1].event?.id)
  }

  @Test
  fun `subject filter matches case-insensitively and ignores events`() {
    val bus = InProcessWorkflowEventBus()
    val got =
        collect(bus, StreamFilter(subject = WorkflowSubject("Group", " test group ")), 1) {
          bus.publishRun(testRun(1, subjects = listOf(WorkflowSubject("group", "Other"))))
          bus.publishEvent(testEvent(1, runId = 2))
          bus.publishRun(testRun(2, subjects = listOf(group)))
        }
    assertEquals(listOf(2L), got.map { it.run?.id })
  }

  @Test
  fun `workflow filter matches workflow id`() {
    val bus = InProcessWorkflowEventBus()
    val got =
        collect(bus, StreamFilter(workflowId = "a.flow"), 1) {
          bus.publishRun(testRun(1, workflowId = "b.flow"))
          bus.publishRun(testRun(2, workflowId = "a.flow"))
        }
    assertEquals(listOf(2L), got.map { it.run?.id })
  }

  @Test
  fun `subject and workflow filter must both match`() {
    val bus = InProcessWorkflowEventBus()
    val got =
        collect(bus, StreamFilter(subject = group, workflowId = "a.flow"), 1) {
          bus.publishRun(testRun(1, workflowId = "b.flow", subjects = listOf(group)))
          bus.publishRun(
              testRun(2, workflowId = "a.flow", subjects = listOf(WorkflowSubject("group", "x"))))
          bus.publishRun(testRun(3, workflowId = "a.flow", subjects = listOf(group)))
        }
    assertEquals(listOf(3L), got.map { it.run?.id })
  }

  @Test
  fun `adminAll and empty filter get everything`() {
    val bus = InProcessWorkflowEventBus()
    listOf(StreamFilter(adminAll = true), StreamFilter()).forEach { filter ->
      val got =
          collect(bus, filter, 2) {
            bus.publishRun(testRun(1))
            bus.publishEvent(testEvent(1, runId = 9))
          }
      assertEquals(listOf("run", "event"), got.map { it.type })
    }
  }

  @Test
  fun `publishing never blocks on a subscriber that stopped collecting`() {
    val bus = InProcessWorkflowEventBus()
    runBlocking {
      val gate = CompletableDeferred<Unit>()
      val job =
          async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe(StreamFilter(adminAll = true)).collect { gate.await() }
          }
      withTimeout(5_000) { repeat(10_000) { bus.publishRun(testRun(it.toLong())) } }
      assertTrue(job.isActive)
      job.cancel()
    }
  }

  @Test
  fun `a lagging subscriber still sees the newest message`() {
    val bus = InProcessWorkflowEventBus()
    runBlocking {
      val gate = CompletableDeferred<Unit>()
      val received = mutableListOf<Long>()
      val job =
          async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe(StreamFilter(adminAll = true)).collect {
              gate.await()
              received += it.run!!.id
            }
          }
      repeat(5_000) { bus.publishRun(testRun(it.toLong())) }
      gate.complete(Unit)
      withTimeout(5_000) { while (received.lastOrNull() != 4_999L) delay(10) }
      assertTrue(received.size < 5_000)
      job.cancel()
    }
  }
}
