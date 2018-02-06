package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.Suspendable
import co.paralleluniverse.strands.concurrent.Semaphore
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.crypto.random63BitValue
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.internal.FlowStateMachine
import net.corda.core.internal.concurrent.flatMap
import net.corda.core.internal.concurrent.map
import net.corda.core.internal.uncheckedCast
import net.corda.core.messaging.MessageRecipients
import net.corda.core.node.services.PartyInfo
import net.corda.core.node.services.queryBy
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.toFuture
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Change
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.node.internal.InitiatedFlowFactory
import net.corda.node.services.persistence.checkpoints
import net.corda.testing.contracts.DummyContract
import net.corda.testing.contracts.DummyState
import net.corda.testing.core.*
import net.corda.testing.internal.LogHelper
import net.corda.testing.node.InMemoryMessagingNetwork.MessageTransfer
import net.corda.testing.node.InMemoryMessagingNetwork.ServicePeerAllocationStrategy.RoundRobin
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNode
import net.corda.testing.node.MockNodeParameters
import net.corda.testing.node.startFlow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import rx.Notification
import rx.Observable
import java.time.Instant
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FlowFrameworkTests {
    companion object {
        init {
            LogHelper.setLevel("+net.corda.flow")
        }
    }

    private lateinit var mockNet: MockNetwork
    private val receivedSessionMessages = ArrayList<SessionTransfer>()
    private lateinit var aliceNode: MockNode
    private lateinit var bobNode: MockNode
    private lateinit var notaryIdentity: Party
    private lateinit var alice: Party
    private lateinit var bob: Party

    private fun MockNode.flushSmm() {
        (this.smm as StateMachineManagerImpl).executor.flush()
    }

    @Before
    fun start() {
        mockNet = MockNetwork(
                servicePeerAllocationStrategy = RoundRobin(),
                cordappPackages = listOf("net.corda.finance.contracts", "net.corda.testing.contracts")
        )
        aliceNode = mockNet.createNode(MockNodeParameters(legalName = ALICE_NAME))
        bobNode = mockNet.createNode(MockNodeParameters(legalName = BOB_NAME))

        receivedSessionMessagesObservable().forEach { receivedSessionMessages += it }

        // Extract identities
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()
        notaryIdentity = mockNet.defaultNotaryIdentity
    }

    @After
    fun cleanUp() {
        mockNet.stopNodes()
        receivedSessionMessages.clear()
    }

    @Test
    fun `newly added flow is preserved on restart`() {
        aliceNode.services.startFlow(NoOpFlow(nonTerminating = true))
        aliceNode.setAcceptableLiveFiberCountOnStop(1)
        val restoredFlow = aliceNode.restartAndGetRestoredFlow<NoOpFlow>()
        assertThat(restoredFlow.flowStarted).isTrue()
    }

    @Test
    fun `flow can lazily use the serviceHub in its constructor`() {
        val flow = LazyServiceHubAccessFlow()
        aliceNode.services.startFlow(flow)
        assertThat(flow.lazyTime).isNotNull()
    }

    @Test
    fun `exception while fiber suspended`() {
        bobNode.registerFlowFactory(ReceiveFlow::class) { InitiatedSendFlow("Hello", it) }
        val flow = ReceiveFlow(bob)
        val fiber = aliceNode.services.startFlow(flow) as FlowStateMachineImpl
        // Before the flow runs change the suspend action to throw an exception
        val exceptionDuringSuspend = Exception("Thrown during suspend")
        fiber.actionOnSuspend = {
            throw exceptionDuringSuspend
        }
        mockNet.runNetwork()
        assertThatThrownBy {
            fiber.resultFuture.getOrThrow()
        }.isSameAs(exceptionDuringSuspend)
        assertThat(aliceNode.allStateMachines).isEmpty()
        // Make sure the fiber does actually terminate
        assertThat(fiber.isTerminated).isTrue()
    }

    @Test
    fun `flow restarted just after receiving payload`() {
        bobNode.registerFlowFactory(SendFlow::class) { InitiatedReceiveFlow(it).nonTerminating() }
        aliceNode.services.startFlow(SendFlow("Hello", bob))

        // We push through just enough messages to get only the payload sent
        bobNode.pumpReceive()
        bobNode.disableDBCloseOnStop()
        bobNode.setAcceptableLiveFiberCountOnStop(1)
        bobNode.dispose()
        mockNet.runNetwork()
        val restoredFlow = bobNode.restartAndGetRestoredFlow<InitiatedReceiveFlow>()
        assertThat(restoredFlow.receivedPayloads[0]).isEqualTo("Hello")
    }
    
    @Test
    fun `flow loaded from checkpoint will respond to messages from before start`() {
        aliceNode.registerFlowFactory(ReceiveFlow::class) { InitiatedSendFlow("Hello", it) }
        bobNode.services.startFlow(ReceiveFlow(alice).nonTerminating()) // Prepare checkpointed receive flow
        // Make sure the add() has finished initial processing.
        bobNode.flushSmm()
        bobNode.disableDBCloseOnStop()
        bobNode.dispose() // kill receiver
        val restoredFlow = bobNode.restartAndGetRestoredFlow<ReceiveFlow>()
        assertThat(restoredFlow.receivedPayloads[0]).isEqualTo("Hello")
    }

    @Ignore("Some changes in startup order make this test's assumptions fail.")
    @Test
    fun `flow with send will resend on interrupted restart`() {
        val payload = random63BitValue()
        val payload2 = random63BitValue()

        var sentCount = 0
        mockNet.messagingNetwork.sentMessages.toSessionTransfers().filter { it.isPayloadTransfer }.forEach { sentCount++ }
        val charlieNode = mockNet.createNode(MockNodeParameters(legalName = CHARLIE_NAME))
        val secondFlow = charlieNode.registerFlowFactory(PingPongFlow::class) { PingPongFlow(it, payload2) }
        mockNet.runNetwork()
        val charlie = charlieNode.info.singleIdentity()

        // Kick off first send and receive
        bobNode.services.startFlow(PingPongFlow(charlie, payload))
        bobNode.database.transaction {
            assertEquals(1, bobNode.checkpointStorage.checkpoints().size)
        }
        // Make sure the add() has finished initial processing.
        bobNode.flushSmm()
        bobNode.disableDBCloseOnStop()
        // Restart node and thus reload the checkpoint and resend the message with same UUID
        bobNode.dispose()
        bobNode.database.transaction {
            assertEquals(1, bobNode.checkpointStorage.checkpoints().size) // confirm checkpoint
            bobNode.services.networkMapCache.clearNetworkMapCache()
        }
        val node2b = mockNet.createNode(MockNodeParameters(bobNode.id))
        bobNode.manuallyCloseDB()
        val (firstAgain, fut1) = node2b.getSingleFlow<PingPongFlow>()
        // Run the network which will also fire up the second flow. First message should get deduped. So message data stays in sync.
        mockNet.runNetwork()
        node2b.flushSmm()
        fut1.getOrThrow()

        val receivedCount = receivedSessionMessages.count { it.isPayloadTransfer }
        // Check flows completed cleanly and didn't get out of phase
        assertEquals(4, receivedCount, "Flow should have exchanged 4 unique messages")// Two messages each way
        // can't give a precise value as every addMessageHandler re-runs the undelivered messages
        assertTrue(sentCount > receivedCount, "Node restart should have retransmitted messages")
        node2b.database.transaction {
            assertEquals(0, node2b.checkpointStorage.checkpoints().size, "Checkpoints left after restored flow should have ended")
        }
        charlieNode.database.transaction {
            assertEquals(0, charlieNode.checkpointStorage.checkpoints().size, "Checkpoints left after restored flow should have ended")
        }
        assertEquals(payload2, firstAgain.receivedPayload, "Received payload does not match the first value on Node 3")
        assertEquals(payload2 + 1, firstAgain.receivedPayload2, "Received payload does not match the expected second value on Node 3")
        assertEquals(payload, secondFlow.getOrThrow().receivedPayload, "Received payload does not match the (restarted) first value on Node 2")
        assertEquals(payload + 1, secondFlow.getOrThrow().receivedPayload2, "Received payload does not match the expected second value on Node 2")
    }

    @Test
    fun `sending to multiple parties`() {
        val charlieNode = mockNet.createNode(MockNodeParameters(legalName = CHARLIE_NAME))
        val charlie = charlieNode.info.singleIdentity()
        bobNode.registerFlowFactory(SendFlow::class) { InitiatedReceiveFlow(it).nonTerminating() }
        charlieNode.registerFlowFactory(SendFlow::class) { InitiatedReceiveFlow(it).nonTerminating() }
        val payload = "Hello World"
        aliceNode.services.startFlow(SendFlow(payload, bob, charlie))
        mockNet.runNetwork()
        val bobFlow = bobNode.getSingleFlow<InitiatedReceiveFlow>().first
        val charlieFlow = charlieNode.getSingleFlow<InitiatedReceiveFlow>().first
        assertThat(bobFlow.receivedPayloads[0]).isEqualTo(payload)
        assertThat(charlieFlow.receivedPayloads[0]).isEqualTo(payload)

        assertSessionTransfers(bobNode,
                aliceNode sent sessionInit(SendFlow::class, payload = payload) to bobNode,
                bobNode sent sessionConfirm() to aliceNode,
                aliceNode sent normalEnd to bobNode
                //There's no session end from the other flows as they're manually suspended
        )

        assertSessionTransfers(charlieNode,
                aliceNode sent sessionInit(SendFlow::class, payload = payload) to charlieNode,
                charlieNode sent sessionConfirm() to aliceNode,
                aliceNode sent normalEnd to charlieNode
                //There's no session end from the other flows as they're manually suspended
        )

        bobNode.setAcceptableLiveFiberCountOnStop(1)
        charlieNode.setAcceptableLiveFiberCountOnStop(1)
    }

    @Test
    fun `receiving from multiple parties`() {
        val charlieNode = mockNet.createNode(MockNodeParameters(legalName = CHARLIE_NAME))
        val charlie = charlieNode.info.singleIdentity()
        val bobPayload = "Test 1"
        val charliePayload = "Test 2"
        bobNode.registerFlowFactory(ReceiveFlow::class) { InitiatedSendFlow(bobPayload, it) }
        charlieNode.registerFlowFactory(ReceiveFlow::class) { InitiatedSendFlow(charliePayload, it) }
        val multiReceiveFlow = ReceiveFlow(bob, charlie).nonTerminating()
        aliceNode.services.startFlow(multiReceiveFlow)
        aliceNode.setAcceptableLiveFiberCountOnStop(1)
        mockNet.runNetwork()
        assertThat(multiReceiveFlow.receivedPayloads[0]).isEqualTo(bobPayload)
        assertThat(multiReceiveFlow.receivedPayloads[1]).isEqualTo(charliePayload)

        assertSessionTransfers(bobNode,
                aliceNode sent sessionInit(ReceiveFlow::class) to bobNode,
                bobNode sent sessionConfirm() to aliceNode,
                bobNode sent sessionData(bobPayload) to aliceNode,
                bobNode sent normalEnd to aliceNode
        )

        assertSessionTransfers(charlieNode,
                aliceNode sent sessionInit(ReceiveFlow::class) to charlieNode,
                charlieNode sent sessionConfirm() to aliceNode,
                charlieNode sent sessionData(charliePayload) to aliceNode,
                charlieNode sent normalEnd to aliceNode
        )
    }

    @Test
    fun `both sides do a send as their first IO request`() {
        bobNode.registerFlowFactory(PingPongFlow::class) { PingPongFlow(it, 20L) }
        aliceNode.services.startFlow(PingPongFlow(bob, 10L))
        mockNet.runNetwork()

        assertSessionTransfers(
                aliceNode sent sessionInit(PingPongFlow::class, payload = 10L) to bobNode,
                bobNode sent sessionConfirm() to aliceNode,
                bobNode sent sessionData(20L) to aliceNode,
                aliceNode sent sessionData(11L) to bobNode,
                bobNode sent sessionData(21L) to aliceNode,
                aliceNode sent normalEnd to bobNode,
                bobNode sent normalEnd to aliceNode
        )
    }

    @Test
    fun `other side ends before doing expected send`() {
        bobNode.registerFlowFactory(ReceiveFlow::class) { NoOpFlow() }
        val resultFuture = aliceNode.services.startFlow(ReceiveFlow(bob)).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(UnexpectedFlowEndException::class.java).isThrownBy {
            resultFuture.getOrThrow()
        }.withMessageContaining(String::class.java.name)  // Make sure the exception message mentions the type the flow was expecting to receive
    }

    @Test
    fun `receiving unexpected session end before entering sendAndReceive`() {
        bobNode.registerFlowFactory(WaitForOtherSideEndBeforeSendAndReceive::class) { NoOpFlow() }
        val sessionEndReceived = Semaphore(0)
        receivedSessionMessagesObservable().filter { it.message is SessionEnd }.subscribe { sessionEndReceived.release() }
        val resultFuture = aliceNode.services.startFlow(
                WaitForOtherSideEndBeforeSendAndReceive(bob, sessionEndReceived)).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(UnexpectedFlowEndException::class.java).isThrownBy {
            resultFuture.getOrThrow()
        }
    }

    @InitiatingFlow
    private class WaitForOtherSideEndBeforeSendAndReceive(val otherParty: Party,
                                                          @Transient val receivedOtherFlowEnd: Semaphore) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            // Kick off the flow on the other side ...
            val session = initiateFlow(otherParty)
            session.send(1)
            // ... then pause this one until it's received the session-end message from the other side
            receivedOtherFlowEnd.acquire()
            session.sendAndReceive<Int>(2)
        }
    }

    @Test
    fun `non-FlowException thrown on other side`() {
        val erroringFlowFuture = bobNode.registerFlowFactory(ReceiveFlow::class) {
            ExceptionFlow { Exception("evil bug!") }
        }
        val erroringFlowSteps = erroringFlowFuture.flatMap { it.progressSteps }

        val receiveFlow = ReceiveFlow(bob)
        val receiveFlowSteps = receiveFlow.progressSteps
        val receiveFlowResult = aliceNode.services.startFlow(receiveFlow).resultFuture

        mockNet.runNetwork()

        assertThat(erroringFlowSteps.get()).containsExactly(
                Notification.createOnNext(ExceptionFlow.START_STEP),
                Notification.createOnError(erroringFlowFuture.get().exceptionThrown)
        )

        val receiveFlowException = assertFailsWith(UnexpectedFlowEndException::class) {
            receiveFlowResult.getOrThrow()
        }
        assertThat(receiveFlowException.message).doesNotContain("evil bug!")
        assertThat(receiveFlowSteps.get()).containsExactly(
                Notification.createOnNext(ReceiveFlow.START_STEP),
                Notification.createOnError(receiveFlowException)
        )

        assertSessionTransfers(
                aliceNode sent sessionInit(ReceiveFlow::class) to bobNode,
                bobNode sent sessionConfirm() to aliceNode,
                bobNode sent erroredEnd() to aliceNode
        )
    }

    @Test
    fun `FlowException thrown on other side`() {
        val erroringFlow = bobNode.registerFlowFactory(ReceiveFlow::class) {
            ExceptionFlow { MyFlowException("Nothing useful") }
        }
        val erroringFlowSteps = erroringFlow.flatMap { it.progressSteps }

        val receivingFiber = aliceNode.services.startFlow(ReceiveFlow(bob)) as FlowStateMachineImpl

        mockNet.runNetwork()

        assertThatExceptionOfType(MyFlowException::class.java)
                .isThrownBy { receivingFiber.resultFuture.getOrThrow() }
                .withMessage("Nothing useful")
                .withStackTraceContaining(ReceiveFlow::class.java.name)  // Make sure the stack trace is that of the receiving flow
        bobNode.database.transaction {
            assertThat(bobNode.checkpointStorage.checkpoints()).isEmpty()
        }

        assertThat(receivingFiber.isTerminated).isTrue()
        assertThat((erroringFlow.get().stateMachine as FlowStateMachineImpl).isTerminated).isTrue()
        assertThat(erroringFlowSteps.get()).containsExactly(
                Notification.createOnNext(ExceptionFlow.START_STEP),
                Notification.createOnError(erroringFlow.get().exceptionThrown)
        )

        assertSessionTransfers(
                aliceNode sent sessionInit(ReceiveFlow::class) to bobNode,
                bobNode sent sessionConfirm() to aliceNode,
                bobNode sent erroredEnd(erroringFlow.get().exceptionThrown) to aliceNode
        )
        // Make sure the original stack trace isn't sent down the wire
        assertThat((receivedSessionMessages.last().message as ErrorSessionEnd).errorResponse!!.stackTrace).isEmpty()
    }

    @Test
    fun `FlowException propagated in invocation chain`() {
        val charlieNode = mockNet.createNode(MockNodeParameters(legalName = CHARLIE_NAME))
        val charlie = charlieNode.info.singleIdentity()

        charlieNode.registerFlowFactory(ReceiveFlow::class) { ExceptionFlow { MyFlowException("Chain") } }
        bobNode.registerFlowFactory(ReceiveFlow::class) { ReceiveFlow(charlie) }
        val receivingFiber = aliceNode.services.startFlow(ReceiveFlow(bob))
        mockNet.runNetwork()
        assertThatExceptionOfType(MyFlowException::class.java)
                .isThrownBy { receivingFiber.resultFuture.getOrThrow() }
                .withMessage("Chain")
    }

    @Test
    fun `FlowException thrown and there is a 3rd unrelated party flow`() {
        val charlieNode = mockNet.createNode(MockNodeParameters(legalName = CHARLIE_NAME))
        val charlie = charlieNode.info.singleIdentity()

        // Bob will send its payload and then block waiting for the receive from Alice. Meanwhile Alice will move
        // onto Charlie which will throw the exception
        val node2Fiber = bobNode
                .registerFlowFactory(ReceiveFlow::class) { SendAndReceiveFlow(it, "Hello") }
                .map { it.stateMachine }
        charlieNode.registerFlowFactory(ReceiveFlow::class) { ExceptionFlow { MyFlowException("Nothing useful") } }

        val aliceFiber = aliceNode.services.startFlow(ReceiveFlow(bob, charlie)) as FlowStateMachineImpl
        mockNet.runNetwork()

        // Alice will terminate with the error it received from Charlie but it won't propagate that to Bob (as it's
        // not relevant to it) but it will end its session with it
        assertThatExceptionOfType(MyFlowException::class.java).isThrownBy {
            aliceFiber.resultFuture.getOrThrow()
        }
        val bobResultFuture = node2Fiber.getOrThrow().resultFuture
        assertThatExceptionOfType(UnexpectedFlowEndException::class.java).isThrownBy {
            bobResultFuture.getOrThrow()
        }

        assertSessionTransfers(bobNode,
                aliceNode sent sessionInit(ReceiveFlow::class) to bobNode,
                bobNode sent sessionConfirm() to aliceNode,
                bobNode sent sessionData("Hello") to aliceNode,
                aliceNode sent erroredEnd() to bobNode
        )
    }

    private class ConditionalExceptionFlow(val otherPartySession: FlowSession, val sendPayload: Any) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val throwException = otherPartySession.receive<Boolean>().unwrap { it }
            if (throwException) {
                throw MyFlowException("Throwing exception as requested")
            }
            otherPartySession.send(sendPayload)
        }
    }

    @Test
    fun `retry subFlow due to receiving FlowException`() {
        @InitiatingFlow
        class AskForExceptionFlow(val otherParty: Party, val throwException: Boolean) : FlowLogic<String>() {
            @Suspendable
            override fun call(): String = initiateFlow(otherParty).sendAndReceive<String>(throwException).unwrap { it }
        }

        class RetryOnExceptionFlow(val otherParty: Party) : FlowLogic<String>() {
            @Suspendable
            override fun call(): String {
                return try {
                    subFlow(AskForExceptionFlow(otherParty, throwException = true))
                } catch (e: MyFlowException) {
                    subFlow(AskForExceptionFlow(otherParty, throwException = false))
                }
            }
        }

        bobNode.registerFlowFactory(AskForExceptionFlow::class) { ConditionalExceptionFlow(it, "Hello") }
        val resultFuture = aliceNode.services.startFlow(RetryOnExceptionFlow(bob)).resultFuture
        mockNet.runNetwork()
        assertThat(resultFuture.getOrThrow()).isEqualTo("Hello")
    }

    @Test
    fun `serialisation issue in counterparty`() {
        bobNode.registerFlowFactory(ReceiveFlow::class) { InitiatedSendFlow(NonSerialisableData(1), it) }
        val result = aliceNode.services.startFlow(ReceiveFlow(bob)).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(UnexpectedFlowEndException::class.java).isThrownBy {
            result.getOrThrow()
        }
    }

    @Test
    fun `FlowException has non-serialisable object`() {
        bobNode.registerFlowFactory(ReceiveFlow::class) {
            ExceptionFlow { NonSerialisableFlowException(NonSerialisableData(1)) }
        }
        val result = aliceNode.services.startFlow(ReceiveFlow(bob)).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(FlowException::class.java).isThrownBy {
            result.getOrThrow()
        }
    }

    @Test
    fun `wait for transaction`() {
        val ptx = TransactionBuilder(notary = notaryIdentity)
                .addOutputState(DummyState(), DummyContract.PROGRAM_ID)
                .addCommand(dummyCommand(alice.owningKey))
        val stx = aliceNode.services.signInitialTransaction(ptx)

        val committerFiber = aliceNode.registerFlowFactory(WaitingFlows.Waiter::class) {
            WaitingFlows.Committer(it)
        }.map { it.stateMachine }.map { uncheckedCast<FlowStateMachine<*>, FlowStateMachine<Any>>(it) }
        val waiterStx = bobNode.services.startFlow(WaitingFlows.Waiter(stx, alice)).resultFuture
        mockNet.runNetwork()
        assertThat(waiterStx.getOrThrow()).isEqualTo(committerFiber.getOrThrow().resultFuture.getOrThrow())
    }

    @Test
    fun `committer throws exception before calling the finality flow`() {
        val ptx = TransactionBuilder(notary = notaryIdentity)
                .addOutputState(DummyState(), DummyContract.PROGRAM_ID)
                .addCommand(dummyCommand())
        val stx = aliceNode.services.signInitialTransaction(ptx)

        aliceNode.registerFlowFactory(WaitingFlows.Waiter::class) {
            WaitingFlows.Committer(it) { throw Exception("Error") }
        }
        val waiter = bobNode.services.startFlow(WaitingFlows.Waiter(stx, alice)).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(UnexpectedFlowEndException::class.java).isThrownBy {
            waiter.getOrThrow()
        }
    }

    @Test
    fun `verify vault query service is tokenizable by force checkpointing within a flow`() {
        val ptx = TransactionBuilder(notary = notaryIdentity)
                .addOutputState(DummyState(), DummyContract.PROGRAM_ID)
                .addCommand(dummyCommand(alice.owningKey))
        val stx = aliceNode.services.signInitialTransaction(ptx)

        aliceNode.registerFlowFactory(VaultQueryFlow::class) {
            WaitingFlows.Committer(it)
        }
        val result = bobNode.services.startFlow(VaultQueryFlow(stx, alice)).resultFuture

        mockNet.runNetwork()
        assertThat(result.getOrThrow()).isEmpty()
    }

    @Test
    fun `customised client flow`() {
        val receiveFlowFuture = bobNode.registerFlowFactory(SendFlow::class) { InitiatedReceiveFlow(it) }
        aliceNode.services.startFlow(CustomSendFlow("Hello", bob)).resultFuture
        mockNet.runNetwork()
        assertThat(receiveFlowFuture.getOrThrow().receivedPayloads).containsOnly("Hello")
    }

    @Test
    fun `customised client flow which has annotated @InitiatingFlow again`() {
        val result = aliceNode.services.startFlow(IncorrectCustomSendFlow("Hello", bob)).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(IllegalArgumentException::class.java).isThrownBy {
            result.getOrThrow()
        }.withMessageContaining(InitiatingFlow::class.java.simpleName)
    }

    @Test
    fun `upgraded initiating flow`() {
        bobNode.registerFlowFactory(UpgradedFlow::class, initiatedFlowVersion = 1) { InitiatedSendFlow("Old initiated", it) }
        val result = aliceNode.services.startFlow(UpgradedFlow(bob)).resultFuture
        mockNet.runNetwork()
        assertThat(receivedSessionMessages).startsWith(
                aliceNode sent sessionInit(UpgradedFlow::class, flowVersion = 2) to bobNode,
                bobNode sent sessionConfirm(flowVersion = 1) to aliceNode
        )
        val (receivedPayload, node2FlowVersion) = result.getOrThrow()
        assertThat(receivedPayload).isEqualTo("Old initiated")
        assertThat(node2FlowVersion).isEqualTo(1)
    }

    @Test
    fun `upgraded initiated flow`() {
        bobNode.registerFlowFactory(SendFlow::class, initiatedFlowVersion = 2) { UpgradedFlow(it) }
        val initiatingFlow = SendFlow("Old initiating", bob)
        val flowInfo = aliceNode.services.startFlow(initiatingFlow).resultFuture
        mockNet.runNetwork()
        assertThat(receivedSessionMessages).startsWith(
                aliceNode sent sessionInit(SendFlow::class, flowVersion = 1, payload = "Old initiating") to bobNode,
                bobNode sent sessionConfirm(flowVersion = 2) to aliceNode
        )
        assertThat(flowInfo.get().flowVersion).isEqualTo(2)
    }

    @Test
    fun `unregistered flow`() {
        val future = aliceNode.services.startFlow(SendFlow("Hello", bob)).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(UnexpectedFlowEndException::class.java)
                .isThrownBy { future.getOrThrow() }
                .withMessageEndingWith("${SendFlow::class.java.name} is not registered")
    }

    @Test
    fun `unknown class in session init`() {
        aliceNode.sendSessionMessage(SessionInit(random63BitValue(), "not.a.real.Class", 1, "version", null), bob)
        mockNet.runNetwork()
        assertThat(receivedSessionMessages).hasSize(2) // Only the session-init and session-reject are expected
        val reject = receivedSessionMessages.last().message as SessionReject
        assertThat(reject.errorMessage).isEqualTo("Don't know not.a.real.Class")
    }

    @Test
    fun `non-flow class in session init`() {
        aliceNode.sendSessionMessage(SessionInit(random63BitValue(), String::class.java.name, 1, "version", null), bob)
        mockNet.runNetwork()
        assertThat(receivedSessionMessages).hasSize(2) // Only the session-init and session-reject are expected
        val reject = receivedSessionMessages.last().message as SessionReject
        assertThat(reject.errorMessage).isEqualTo("${String::class.java.name} is not a flow")
    }

    @Test
    fun `single inlined sub-flow`() {
        bobNode.registerFlowFactory(SendAndReceiveFlow::class) { SingleInlinedSubFlow(it) }
        val result = aliceNode.services.startFlow(SendAndReceiveFlow(bob, "Hello")).resultFuture
        mockNet.runNetwork()
        assertThat(result.getOrThrow()).isEqualTo("HelloHello")
    }

    @Test
    fun `double inlined sub-flow`() {
        bobNode.registerFlowFactory(SendAndReceiveFlow::class) { DoubleInlinedSubFlow(it) }
        val result = aliceNode.services.startFlow(SendAndReceiveFlow(bob, "Hello")).resultFuture
        mockNet.runNetwork()
        assertThat(result.getOrThrow()).isEqualTo("HelloHello")
    }

    @Test
    fun `double initiateFlow throws`() {
        val future = aliceNode.services.startFlow(DoubleInitiatingFlow()).resultFuture
        mockNet.runNetwork()
        assertThatExceptionOfType(IllegalStateException::class.java)
                .isThrownBy { future.getOrThrow() }
                .withMessageContaining("Attempted to initiateFlow() twice")
    }

    @InitiatingFlow
    private class DoubleInitiatingFlow : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            initiateFlow(ourIdentity)
            initiateFlow(ourIdentity)
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //region Helpers

    private inline fun <reified P : FlowLogic<*>> MockNode.restartAndGetRestoredFlow() = run {
        disableDBCloseOnStop() // Handover DB to new node copy
        stop()
        val newNode = mockNet.createNode(MockNodeParameters(id, configuration.myLegalName))
        newNode.setAcceptableLiveFiberCountOnStop(1)
        manuallyCloseDB()
        mockNet.runNetwork()
        newNode.getSingleFlow<P>().first
    }

    private inline fun <reified P : FlowLogic<*>> MockNode.getSingleFlow(): Pair<P, CordaFuture<*>> {
        return findStateMachines(P::class.java).single { it.javaClass == P::class.java }
    }

    private inline fun <reified P : FlowLogic<*>> MockNode.registerFlowFactory(
            initiatingFlowClass: KClass<out FlowLogic<*>>,
            initiatedFlowVersion: Int = 1,
            noinline flowFactory: (FlowSession) -> P): CordaFuture<P> {
        val observable = internalRegisterFlowFactory(
                initiatingFlowClass.java,
                InitiatedFlowFactory.CorDapp(initiatedFlowVersion, "", flowFactory),
                P::class.java,
                track = true)
        return observable.toFuture()
    }

    private fun sessionInit(clientFlowClass: KClass<out FlowLogic<*>>, flowVersion: Int = 1, payload: Any? = null): SessionInit {
        return SessionInit(0, clientFlowClass.java.name, flowVersion, "", payload?.serialize())
    }

    private fun sessionConfirm(flowVersion: Int = 1) = SessionConfirm(0, 0, flowVersion, "")
    private fun sessionData(payload: Any) = SessionData(0, payload.serialize())
    private val normalEnd = NormalSessionEnd(0)
    private fun erroredEnd(errorResponse: FlowException? = null) = ErrorSessionEnd(0, errorResponse)

    private fun MockNode.sendSessionMessage(message: SessionMessage, destination: Party) {
        services.networkService.apply {
            val address = getAddressOfParty(PartyInfo.SingleNode(destination, emptyList()))
            send(createMessage(StateMachineManagerImpl.sessionTopic, message.serialize().bytes), address)
        }
    }

    private fun assertSessionTransfers(vararg expected: SessionTransfer) {
        assertThat(receivedSessionMessages).containsExactly(*expected)
    }

    private fun assertSessionTransfers(node: MockNode, vararg expected: SessionTransfer): List<SessionTransfer> {
        val actualForNode = receivedSessionMessages.filter { it.from == node.id || it.to == node.services.networkService.myAddress }
        assertThat(actualForNode).containsExactly(*expected)
        return actualForNode
    }

    private data class SessionTransfer(val from: Int, val message: SessionMessage, val to: MessageRecipients) {
        val isPayloadTransfer: Boolean get() = message is SessionData || message is SessionInit && message.firstPayload != null
        override fun toString(): String = "$from sent $message to $to"
    }

    private fun receivedSessionMessagesObservable(): Observable<SessionTransfer> {
        return mockNet.messagingNetwork.receivedMessages.toSessionTransfers()
    }

    private fun Observable<MessageTransfer>.toSessionTransfers(): Observable<SessionTransfer> {
        return filter { it.message.topicSession == StateMachineManagerImpl.sessionTopic }.map {
            val from = it.sender.id
            val message = it.message.data.deserialize<SessionMessage>()
            SessionTransfer(from, sanitise(message), it.recipients)
        }
    }

    private fun sanitise(message: SessionMessage) = when (message) {
        is SessionData -> message.copy(recipientSessionId = 0)
        is SessionInit -> message.copy(initiatorSessionId = 0, appName = "")
        is SessionConfirm -> message.copy(initiatorSessionId = 0, initiatedSessionId = 0, appName = "")
        is NormalSessionEnd -> message.copy(recipientSessionId = 0)
        is ErrorSessionEnd -> message.copy(recipientSessionId = 0)
        else -> message
    }

    private infix fun MockNode.sent(message: SessionMessage): Pair<Int, SessionMessage> = Pair(id, message)
    private infix fun Pair<Int, SessionMessage>.to(node: MockNode): SessionTransfer = SessionTransfer(first, second, node.network.myAddress)

    private val FlowLogic<*>.progressSteps: CordaFuture<List<Notification<ProgressTracker.Step>>>
        get() {
            return progressTracker!!.changes
                    .ofType(Change.Position::class.java)
                    .map { it.newStep }
                    .materialize()
                    .toList()
                    .toFuture()
        }

    private class LazyServiceHubAccessFlow : FlowLogic<Unit>() {
        val lazyTime: Instant by lazy { serviceHub.clock.instant() }
        @Suspendable
        override fun call() = Unit
    }

    private class NoOpFlow(val nonTerminating: Boolean = false) : FlowLogic<Unit>() {
        @Transient
        var flowStarted = false

        @Suspendable
        override fun call() {
            flowStarted = true
            if (nonTerminating) {
                Fiber.park()
            }
        }
    }

    @InitiatingFlow
    private open class SendFlow(val payload: Any, vararg val otherParties: Party) : FlowLogic<FlowInfo>() {
        init {
            require(otherParties.isNotEmpty())
        }

        @Suspendable
        override fun call(): FlowInfo {
            val flowInfos = otherParties.map {
                val session = initiateFlow(it)
                session.send(payload)
                session.getCounterpartyFlowInfo()
            }.toList()
            return flowInfos.first()
        }
    }

    private open class InitiatedSendFlow(val payload: Any, val otherPartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() = otherPartySession.send(payload)
    }

    private interface CustomInterface

    private class CustomSendFlow(payload: String, otherParty: Party) : CustomInterface, SendFlow(payload, otherParty)

    @InitiatingFlow
    private class IncorrectCustomSendFlow(payload: String, otherParty: Party) : CustomInterface, SendFlow(payload, otherParty)

    @InitiatingFlow
    private class ReceiveFlow(vararg val otherParties: Party) : FlowLogic<Unit>() {
        object START_STEP : ProgressTracker.Step("Starting")
        object RECEIVED_STEP : ProgressTracker.Step("Received")

        init {
            require(otherParties.isNotEmpty())
        }

        override val progressTracker: ProgressTracker = ProgressTracker(START_STEP, RECEIVED_STEP)
        private var nonTerminating: Boolean = false
        @Transient
        var receivedPayloads: List<String> = emptyList()

        @Suspendable
        override fun call() {
            progressTracker.currentStep = START_STEP
            receivedPayloads = otherParties.map { initiateFlow(it).receive<String>().unwrap { it } }
            progressTracker.currentStep = RECEIVED_STEP
            if (nonTerminating) {
                Fiber.park()
            }
        }

        fun nonTerminating(): ReceiveFlow {
            nonTerminating = true
            return this
        }
    }

    private class InitiatedReceiveFlow(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
        object START_STEP : ProgressTracker.Step("Starting")
        object RECEIVED_STEP : ProgressTracker.Step("Received")

        override val progressTracker: ProgressTracker = ProgressTracker(START_STEP, RECEIVED_STEP)
        private var nonTerminating: Boolean = false
        @Transient
        var receivedPayloads: List<String> = emptyList()

        @Suspendable
        override fun call() {
            progressTracker.currentStep = START_STEP
            receivedPayloads = listOf(otherPartySession.receive<String>().unwrap { it })
            progressTracker.currentStep = RECEIVED_STEP
            if (nonTerminating) {
                Fiber.park()
            }
        }

        fun nonTerminating(): InitiatedReceiveFlow {
            nonTerminating = true
            return this
        }
    }

    @InitiatingFlow
    private class SendAndReceiveFlow(val otherParty: Party, val payload: Any, val otherPartySession: FlowSession? = null) : FlowLogic<Any>() {
        constructor(otherPartySession: FlowSession, payload: Any) : this(otherPartySession.counterparty, payload, otherPartySession)

        @Suspendable
        override fun call(): Any = (otherPartySession ?: initiateFlow(otherParty)).sendAndReceive<Any>(payload).unwrap { it }
    }

    private class InlinedSendFlow(val payload: String, val otherPartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() = otherPartySession.send(payload)
    }

    @InitiatingFlow
    private class PingPongFlow(val otherParty: Party, val payload: Long, val otherPartySession: FlowSession? = null) : FlowLogic<Unit>() {
        constructor(otherPartySession: FlowSession, payload: Long) : this(otherPartySession.counterparty, payload, otherPartySession)

        @Transient
        var receivedPayload: Long? = null
        @Transient
        var receivedPayload2: Long? = null

        @Suspendable
        override fun call() {
            val session = otherPartySession ?: initiateFlow(otherParty)
            receivedPayload = session.sendAndReceive<Long>(payload).unwrap { it }
            receivedPayload2 = session.sendAndReceive<Long>(payload + 1).unwrap { it }
        }
    }

    private class ExceptionFlow<E : Exception>(val exception: () -> E) : FlowLogic<Nothing>() {
        object START_STEP : ProgressTracker.Step("Starting")

        override val progressTracker: ProgressTracker = ProgressTracker(START_STEP)
        lateinit var exceptionThrown: E

        @Suspendable
        override fun call(): Nothing {
            progressTracker.currentStep = START_STEP
            exceptionThrown = exception()
            throw exceptionThrown
        }
    }

    private class MyFlowException(override val message: String) : FlowException() {
        override fun equals(other: Any?): Boolean = other is MyFlowException && other.message == this.message
        override fun hashCode(): Int = message.hashCode()
    }

    private object WaitingFlows {
        @InitiatingFlow
        class Waiter(val stx: SignedTransaction, val otherParty: Party) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val otherPartySession = initiateFlow(otherParty)
                otherPartySession.send(stx)
                return waitForLedgerCommit(stx.id)
            }
        }

        class Committer(val otherPartySession: FlowSession, val throwException: (() -> Exception)? = null) : FlowLogic<SignedTransaction>() {
            @Suspendable
            override fun call(): SignedTransaction {
                val stx = otherPartySession.receive<SignedTransaction>().unwrap { it }
                if (throwException != null) throw throwException.invoke()
                return subFlow(FinalityFlow(stx, setOf(otherPartySession.counterparty)))
            }
        }
    }

    @InitiatingFlow
    private class VaultQueryFlow(val stx: SignedTransaction, val otherParty: Party) : FlowLogic<List<StateAndRef<ContractState>>>() {
        @Suspendable
        override fun call(): List<StateAndRef<ContractState>> {
            val otherPartySession = initiateFlow(otherParty)
            otherPartySession.send(stx)
            // hold onto reference here to force checkpoint of vaultService and thus
            // prove it is registered as a tokenizableService in the node
            val vaultQuerySvc = serviceHub.vaultService
            waitForLedgerCommit(stx.id)
            return vaultQuerySvc.queryBy<ContractState>().states
        }
    }

    @InitiatingFlow(version = 2)
    private class UpgradedFlow(val otherParty: Party, val otherPartySession: FlowSession? = null) : FlowLogic<Pair<Any, Int>>() {
        constructor(otherPartySession: FlowSession) : this(otherPartySession.counterparty, otherPartySession)

        @Suspendable
        override fun call(): Pair<Any, Int> {
            val otherPartySession = this.otherPartySession ?: initiateFlow(otherParty)
            val received = otherPartySession.receive<Any>().unwrap { it }
            val otherFlowVersion = otherPartySession.getCounterpartyFlowInfo().flowVersion
            return Pair(received, otherFlowVersion)
        }
    }

    private class SingleInlinedSubFlow(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            val payload = otherPartySession.receive<String>().unwrap { it }
            subFlow(InlinedSendFlow(payload + payload, otherPartySession))
        }
    }

    private class DoubleInlinedSubFlow(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            subFlow(SingleInlinedSubFlow(otherPartySession))
        }
    }

    private data class NonSerialisableData(val a: Int)
    private class NonSerialisableFlowException(@Suppress("unused") val data: NonSerialisableData) : FlowException()

    //endregion Helpers
}
