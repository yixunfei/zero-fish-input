package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiToolRunTest {
    @Test fun rejectedUiDeliveryPreventsNetworkDispatchAndQueuedApprovalsDisappearAfterStop() {
        val f = Fixture(); var requests = 0
        var deliver: (Result<AiToolResponse>) -> Unit = {}
        val provider = object : AiToolProvider {
            override fun request(request: AiToolRequest, completed: (Result<AiToolResponse>) -> Unit): AiRequestHandle {
                requests++; deliver = completed; return f.handle()
            }
        }
        val executor = object : AiToolExecutor {
            override fun execute(call: AiToolCall, current: () -> Boolean, completed: (AiToolResult) -> Unit) = f.handle()
        }
        val rejected = AiToolRun(provider, { true }, { false }, { _, _ -> AutoCloseable {} }, {})
        rejected.start(AiToolRequest("Public task", listOf(f.definition)), mapOf("read" to executor))
        assertEquals(0, requests); assertFalse(rejected.running)
        val ui = ArrayDeque<() -> Unit>(); val events = mutableListOf<AiToolRunEvent>()
        val queued = AiToolRun(provider, { true }, { ui.add(it); true }, { _, _ -> AutoCloseable {} }, events::add)
        queued.start(AiToolRequest("Public task", listOf(f.definition)), mapOf("read" to executor))
        ui.removeFirst()()
        deliver(Result.success(AiToolResponse("", f.call())))
        ui.removeFirst()()
        events.clear()
        queued.stop()
        while (ui.isNotEmpty()) ui.removeFirst()()
        assertEquals(listOf(AiToolRunState.STOPPED), events.map { it.state })
    }

    @Test fun modelCannotExecuteBeforeApprovalAndRejectedCallsAreNotDispatched() {
        val f = Fixture(); f.start(); f.reply(AiToolResponse("", f.call()))
        assertEquals(0, f.executions)
        val approval = f.events.last().approval!!
        assertFalse(f.run.approve("forged", true)); assertEquals(0, f.executions)
        assertTrue(f.run.approve(approval, false)); assertEquals(0, f.executions)
        assertEquals(AiToolResultStatus.REJECTED, f.requests.last().turns.single().result.status)
        assertFalse(f.run.approve(approval, true))
        f.reply(AiToolResponse("Public answer"))
        assertFalse(f.run.running); assertEquals(AiToolRunState.COMPLETED, f.events.last().state)
    }

    @Test fun cancelledAndDuplicateCallbacksCannotAdvanceANewTask() {
        val f = Fixture(); f.start(); f.reply(AiToolResponse("", f.call()))
        assertTrue(f.run.approve(f.events.last().approval!!, true)); assertEquals(1, f.executions)
        val late = f.deliver!!; val current = f.current!!
        f.run.stop(); assertFalse(current())
        f.start(); late(AiToolResult("call", AiToolResultStatus.SUCCESS, "Late public fixture"))
        assertEquals(2, f.requests.size)
        f.reply(AiToolResponse("Final public answer"))
        late(AiToolResult("call", AiToolResultStatus.SUCCESS, "Repeated public fixture"))
        assertEquals("Final public answer", f.events.last().message)
    }

    @Test fun unknownToolsExtraArgumentsAndRepeatedIdsFailClosed() {
        for (call in listOf(AiToolCall("call", "unknown", "{}"), AiToolCall("call", "read", "{\"unexpected\":true}"),
            AiToolCall("call", "read", "{\"file\":1}"), AiToolCall("call", "read", "{\"file\":\"x\",\"file\":\"y\"}"))) {
            val f = Fixture(); f.start(); f.reply(AiToolResponse("", call))
            assertEquals(AiToolRunFailure.INVALID_CALL, f.events.last().failure); assertEquals(0, f.executions)
        }
        val f = Fixture(); f.start(); f.reply(AiToolResponse("", f.call()))
        f.run.approve(f.events.last().approval!!, false)
        f.reply(AiToolResponse("", f.call()))
        assertEquals(AiToolRunFailure.INVALID_CALL, f.events.last().failure)
    }

    @Test fun uncertainWritesStopWithoutRetryAndRevocationCancelsDispatch() {
        val f = Fixture(AiToolEffect.WRITE); f.start(); f.reply(AiToolResponse("", f.call()))
        f.run.approve(f.events.last().approval!!, true)
        f.deliver!!(AiToolResult("call", AiToolResultStatus.UNKNOWN, ""))
        assertEquals(1, f.requests.size); assertEquals(AiToolRunFailure.UNCERTAIN, f.events.last().failure)
        val revoked = Fixture(); revoked.start(); revoked.allowed = false
        revoked.reply(AiToolResponse("", revoked.call()))
        assertFalse(revoked.run.running); assertEquals(0, revoked.executions)
    }

    @Test fun deadlineAndRoundLimitReleaseTaskEvenWhenPolicyWasRevoked() {
        val f = Fixture(); f.start(); f.allowed = false; f.timeout!!()
        assertFalse(f.run.running)
        val loops = Fixture(); loops.start()
        repeat(20) { index ->
            loops.reply(AiToolResponse("", loops.call("call$index")))
            loops.run.approve(loops.events.last().approval!!, false)
        }
        assertEquals(20, loops.requests.size); assertEquals(AiToolRunFailure.LIMIT, loops.events.last().failure)
    }

    private class Fixture(effect: AiToolEffect = AiToolEffect.READ) {
        var allowed = true
        val events = mutableListOf<AiToolRunEvent>()
        val requests = mutableListOf<AiToolRequest>()
        var reply: (Result<AiToolResponse>) -> Unit = {}
        var deliver: ((AiToolResult) -> Unit)? = null
        var current: (() -> Boolean)? = null
        var timeout: (() -> Unit)? = null
        var executions = 0
        val definition = AiToolDefinition("files/read", "read", "Read", "Read public fixture",
            """{"type":"object","properties":{"file":{"type":"string","enum":["x"]}},"required":["file"],"additionalProperties":false}""",
            AiToolOrigin.FILE, effect)
        val run = AiToolRun(object : AiToolProvider {
            override fun request(request: AiToolRequest, completed: (Result<AiToolResponse>) -> Unit): AiRequestHandle {
                requests += request; reply = completed; return handle()
            }
        }, { allowed }, { it(); true }, { _, callback -> timeout = callback; AutoCloseable {} }, events::add)
        fun call(id: String = "call") = AiToolCall(id, "read", """{"file":"x"}""")
        fun reply(value: AiToolResponse) = reply(Result.success(value))
        fun start() {
            assertTrue(run.start(AiToolRequest("Public task", listOf(definition)), mapOf("read" to object : AiToolExecutor {
                override fun execute(call: AiToolCall, current: () -> Boolean, completed: (AiToolResult) -> Unit): AiRequestHandle {
                    assertTrue(current()); executions++; this@Fixture.current = current; deliver = completed; return handle()
                }
            })))
        }
        fun handle() = object : AiRequestHandle { override fun cancel() = Unit }
    }
}
