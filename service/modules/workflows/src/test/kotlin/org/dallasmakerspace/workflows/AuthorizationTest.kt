package org.dallasmakerspace.workflows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonObject
import org.dallasmakerspace.models.workflows.WorkflowInputField

private class ScriptedAuthorizer(
    var decision: suspend (String, JsonObject, String) -> AuthorizationDecision = { _, _, _ ->
      AuthorizationDecision.Allowed
    },
) : WorkflowStartAuthorizer {
  val calls = mutableListOf<Triple<String, JsonObject, String>>()

  override suspend fun authorize(
      workflowId: String,
      input: JsonObject,
      actor: String,
  ): AuthorizationDecision {
    calls += Triple(workflowId, input, actor)
    return decision(workflowId, input, actor)
  }
}

class AuthorizationTest {
  private val log = mutableListOf<String>()
  private val step = TestStep("only", log)

  private fun workflow(authorization: String? = "GROUP_ADMIN") =
      TestWorkflow(listOf(StepSpec(step)), authorization = authorization)

  private fun harness(
      authorizer: WorkflowStartAuthorizer? = null,
      authorization: String? = "GROUP_ADMIN",
  ): Harness {
    val wf = workflow(authorization)
    return Harness(
        wf, authorizers = authorizer?.let { mapOf("GROUP_ADMIN" to it) } ?: emptyMap())
  }

  @Test
  fun anAllowedActorGetsThePlanAndTheRun() = blocking {
    val authorizer = ScriptedAuthorizer()
    val h = harness(authorizer)

    val plan = h.service.preflight(h.workflow.id, input(), "jdoe")
    val started = h.service.start(h.workflow.id, input(), "jdoe", "TEST", "tok")

    assertTrue(plan.canStart)
    assertIs<StartResult.Started>(started)
    assertEquals(2, authorizer.calls.size)
    assertEquals(Triple("test.flow", input(), "jdoe"), authorizer.calls.first())
  }

  @Test
  fun aDeniedActorIsForbiddenOnPreflightAndStartAndNothingIsStarted() = blocking {
    val authorizer =
        ScriptedAuthorizer({ _, _, _ -> AuthorizationDecision.Denied("not for you") })
    val h = harness(authorizer)

    val thrown =
        assertFailsWith<WorkflowForbiddenException> {
          h.service.preflight(h.workflow.id, input(), "jdoe")
        }
    val started = h.service.start(h.workflow.id, input(), "jdoe", "TEST", "tok")

    assertEquals("not for you", thrown.reason)
    assertEquals("not for you", assertIs<StartResult.Forbidden>(started).reason)
    assertTrue(h.service.listRuns(RunFilter()).isEmpty())
    assertTrue(log.isEmpty())
  }

  @Test
  fun aBlankDenialReasonFallsBackToAGenericOne() = blocking {
    val h = harness(ScriptedAuthorizer({ _, _, _ -> AuthorizationDecision.Denied("") }))

    val started = h.service.start(h.workflow.id, input(), "jdoe", "TEST", "tok")

    assertTrue(assertIs<StartResult.Forbidden>(started).reason.isNotBlank())
  }

  @Test
  fun aWorkflowWithoutAuthorizationIgnoresAuthorizers() = blocking {
    val authorizer =
        ScriptedAuthorizer({ _, _, _ -> AuthorizationDecision.Denied("never asked") })
    val h = harness(authorizer, authorization = null)

    assertIs<StartResult.Started>(h.service.start(h.workflow.id, input(), "jdoe", "TEST", "tok"))
    assertTrue(authorizer.calls.isEmpty())
  }

  @Test
  fun noAuthorizerForTheModeFailsClosed() = blocking {
    val h = harness(authorizer = null)

    assertFailsWith<WorkflowForbiddenException> {
      h.service.preflight(h.workflow.id, input(), "jdoe")
    }
    assertIs<StartResult.Forbidden>(h.service.start(h.workflow.id, input(), "jdoe", "TEST", "tok"))
    assertTrue(h.service.listRuns(RunFilter()).isEmpty())
  }

  @Test
  fun anAuthorizerRegisteredForAnotherModeDoesNotCount() = blocking {
    val wf = workflow("SOMETHING_ELSE")
    val h = Harness(wf, authorizers = mapOf("GROUP_ADMIN" to ScriptedAuthorizer()))

    assertIs<StartResult.Forbidden>(h.service.start(wf.id, input(), "jdoe", "TEST", "tok"))
  }

  @Test
  fun anAuthorizerThatThrowsFailsClosedWithoutLeakingTheError() = blocking {
    val h =
        harness(ScriptedAuthorizer({ _, _, _ -> error("ldap://secret-host unreachable") }))

    val thrown =
        assertFailsWith<WorkflowForbiddenException> {
          h.service.preflight(h.workflow.id, input(), "jdoe")
        }
    val started = h.service.start(h.workflow.id, input(), "jdoe", "TEST", "tok")

    assertTrue(!thrown.reason.contains("secret-host"))
    assertTrue(!assertIs<StartResult.Forbidden>(started).reason.contains("secret-host"))
    assertTrue(h.service.listRuns(RunFilter()).isEmpty())
  }

  @Test
  fun anAuthorizerThatHangsFailsClosedAfterTheTimeout() = blocking {
    val h = harness(ScriptedAuthorizer({ _, _, _ -> awaitCancellation() }))
    h.settings.preflightStepTimeoutMillis = 50

    assertIs<StartResult.Forbidden>(h.service.start(h.workflow.id, input(), "jdoe", "TEST", "tok"))
  }

  @Test
  fun invalidInputIsReportedBeforeAuthorizationAndAuthorizationBeforeLockLookups() = blocking {
    val authorizer =
        ScriptedAuthorizer({ _, _, _ -> AuthorizationDecision.Denied("no") })
    val h = harness(authorizer)

    assertFailsWith<InvalidWorkflowInputException> {
      h.service.preflight(h.workflow.id, input(name = " "), "jdoe")
    }
    assertTrue(authorizer.calls.isEmpty())

    val locked = Harness(workflow(null), clock = h.clock, store = h.store)
    assertIs<StartResult.Started>(locked.service.start(h.workflow.id, input(), "jdoe", "T", "t1"))
    val result = h.service.start(h.workflow.id, input(), "asmith", "T", "t2")
    assertIs<StartResult.Forbidden>(result)
    assertFailsWith<WorkflowForbiddenException> {
      h.service.preflight(h.workflow.id, input(), "asmith")
    }
  }

  // definitions

  @Test
  fun definitionsCarryTheAuthorizationAndConfirmationAndFieldMetadata() {
    val fields =
        listOf(
            WorkflowInputField("name", "Name"),
            WorkflowInputField(
                "reason", "Reason", multiline = true, minLength = 3, maxLength = 9, hint = "h"))
    val wf =
        TestWorkflow(
            listOf(StepSpec(step)),
            authorization = "GROUP_ADMIN",
            confirmTypingField = "name",
            inputFields = fields)

    val def = WorkflowRegistry(setOf(wf)).definitions().single()

    assertEquals("GROUP_ADMIN", def.authorization)
    assertEquals("name", def.confirmTypingField)
    assertEquals(fields, def.inputFields)
  }

  @Test
  fun definitionsDefaultToNoAuthorizationAndNoConfirmation() {
    val def = WorkflowRegistry(setOf(workflow(authorization = null))).definitions().single()

    assertNull(def.authorization)
    assertNull(def.confirmTypingField)
  }

  @Test
  fun theRegistryRejectsAConfirmationFieldThatIsNotAnInput() {
    val wf = TestWorkflow(listOf(StepSpec(step)), confirmTypingField = "missing")

    val thrown = assertFailsWith<IllegalArgumentException> { WorkflowRegistry(setOf(wf)) }

    assertTrue(thrown.message!!.contains("missing"))
  }
}
