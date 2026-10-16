package org.dallasmakerspace.server.workflows

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** The launch dialog is browser JS: pure rules run under Node when present, markup by source checks. */
class WorkflowLaunchDialogTest {
  private fun resource(path: String): String =
      checkNotNull(javaClass.classLoader.getResource(path)) { "missing $path" }.readText()

  private val fieldsJs = resource("static/js/workflow-fields.js")
  private val launchJs = resource("static/js/workflow-launch.js")

  private fun nodeOrNull(): String? =
      try {
        val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) "node" else null
      } catch (e: java.io.IOException) {
        null
      }

  @Test
  fun `field rules, counter, confirm match and error text behave as specified`() {
    val node = nodeOrNull()
    assumeTrue("node is not installed", node != null)
    val dir = kotlin.io.path.createTempDirectory("wf-fields").toFile()
    try {
      val lib = File(dir, "workflow-fields.js").apply { writeText(fieldsJs) }
      val script = File(dir, "check.js").apply { writeText(CHECK_SCRIPT) }
      val p =
          ProcessBuilder(node, script.absolutePath, lib.absolutePath)
              .redirectErrorStream(true)
              .start()
      val out = p.inputStream.bufferedReader().readText()
      assertTrue(p.waitFor(30, TimeUnit.SECONDS), "node timed out")
      assertEquals(0, p.exitValue(), out)
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `the dialog renders fields from the definition and nothing is hard-coded for archive`() {
    assertTrue("f.multiline" in launchJs && "'textarea'" in launchJs)
    assertTrue("f.hint" in launchJs && "form-text" in launchJs)
    assertTrue("F.fieldError" in launchJs && "F.counterText" in launchJs)
    assertTrue("wf-counter" in launchJs)
    assertTrue("def.confirmTypingField" in launchJs)
    assertTrue("F.confirmMatches" in launchJs)
    assertTrue("F.blockedItems" in launchJs && "alert-danger wf-blocked" in launchJs)
    assertTrue("F.errorText(r.status" in launchJs)
    assertFalse("group.archive" in launchJs)
    assertFalse("reason" in launchJs.lowercase())
    assertFalse("groupName" in launchJs)
  }

  @Test
  fun `the confirm button stays disabled until blocked items are clear and the text matches`() {
    assertTrue("startable && confirmed()" in launchJs)
    assertTrue("!!plan.canStart && !plan.activeRun" in launchJs)
    assertTrue("if (!confirmed()) return;" in launchJs)
  }

  @Test
  fun `the idempotency token is still sent and the reason travels in the input`() {
    assertTrue("{input: input, idempotencyToken: token}" in launchJs)
    assertTrue("{input: input}" in launchJs)
    assertTrue("input[f.name] = v" in launchJs)
  }

  companion object {
    private val CHECK_SCRIPT =
        """
        const assert = require('assert');
        const F = require(process.argv[2]);
        const reason = {name: 'reason', required: true, minLength: 10, maxLength: 20};
        assert.ok(F.fieldError(reason, '') !== null, 'required empty');
        assert.ok(F.fieldError(reason, '   ') !== null, 'blank is empty');
        assert.ok(F.fieldError(reason, 'too short') !== null, 'below min');
        assert.strictEqual(F.fieldError(reason, '  ten chars!  '), null, 'trimmed length counts');
        assert.ok(F.fieldError(reason, 'x'.repeat(21)) !== null, 'above max');
        assert.strictEqual(F.fieldError(reason, 'x'.repeat(20)), null, 'exactly max');
        assert.strictEqual(F.fieldError({name: 'n', required: false}, ''), null, 'optional empty');
        assert.ok(F.fieldError({name: 'n'}, '') !== null, 'required defaults to true');
        assert.ok(F.fieldError({name: 'n', required: false, minLength: 3}, 'ab') !== null, 'optional min');
        assert.strictEqual(F.counterText(reason, 'abc'), '3 / 20 (min 10)');
        assert.strictEqual(F.counterText({maxLength: 5}, 'abc'), '3 / 5');
        assert.strictEqual(F.counterText({minLength: 4}, 'abc'), '3 characters (min 4)');
        assert.strictEqual(F.counterText({}, 'abc'), '');
        assert.ok(F.confirmMatches('Example Group', 'Example Group'));
        assert.ok(F.confirmMatches('  Example Group  ', 'Example Group'));
        assert.ok(!F.confirmMatches('example group', 'Example Group'), 'case-sensitive');
        assert.ok(!F.confirmMatches('Example Grou', 'Example Group'));
        assert.ok(!F.confirmMatches('', ''), 'empty expected never matches');
        assert.ok(!F.confirmMatches('x', undefined));
        const plan = {canStart: false, steps: [
          {stepId: 'a', displayName: 'Export', status: 'WILL_RUN', messages: ['ok']},
          {stepId: 'b', displayName: 'Check references', status: 'BLOCKED', messages: ['m1', 'm2']},
          {stepId: 'c', status: 'BLOCKED'}]};
        const blocked = F.blockedItems(plan);
        assert.strictEqual(blocked.length, 2);
        assert.deepStrictEqual(blocked[0], {step: 'Check references', messages: ['m1', 'm2']});
        assert.deepStrictEqual(blocked[1], {step: 'c', messages: []});
        assert.deepStrictEqual(F.blockedItems(null), []);
        assert.ok(/not allowed/.test(F.errorText(403, {message: 'Forbidden'}, 'x')));
        assert.ok(/locked/.test(F.errorText(409, {message: 'Conflict'}, 'x')));
        assert.strictEqual(F.errorText(409, {message: 'Group is locked by run 5'}, 'x'), 'Group is locked by run 5');
        assert.strictEqual(F.errorText(500, {message: 'boom'}, 'x'), 'boom');
        assert.strictEqual(F.errorText(500, null, 'fallback'), 'fallback');
        """
            .trimIndent()
  }
}
