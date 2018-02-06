package net.corda.docs.tutorial.mocknetwork

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.requireThat
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.identity.Party
import net.corda.core.messaging.MessageRecipients
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.unwrap
import net.corda.node.services.messaging.Message
import net.corda.node.services.statemachine.SessionData
import net.corda.testing.node.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class TutorialMockNetwork {

    @InitiatingFlow
    class FlowA(private val otherParty: Party) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val session = initiateFlow(otherParty)

            session.receive<Int>().unwrap {
                requireThat { "Expected to receive 1" using (it == 1) }
            }

            session.receive<Int>().unwrap {
                requireThat { "Expected to receive 2" using (it == 2) }
            }
        }
    }

    @InitiatedBy(FlowA::class)
    class FlowB(private val session: FlowSession) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            session.send(1)
            session.send(2)
        }
    }

    lateinit private var mockNet: MockNetwork
    lateinit private var nodeA: MockNode
    lateinit private var nodeB: MockNode

    @Rule
    @JvmField
    val expectedEx: ExpectedException = ExpectedException.none()

    @Before
    fun setUp() {
        mockNet = MockNetwork(emptyList())
        nodeA = mockNet.createPartyNode()
        nodeB = mockNet.createPartyNode()
        nodeB.registerInitiatedFlow(FlowB::class.java)
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
    }

    @Test
    fun `fail if initiated doesn't send back 1 on first result`() {

        // DOCSTART 1
        // modify message if it's 1
        nodeB.setMessagingServiceSpy(object : MessagingServiceSpy(nodeB.network) {

            override fun send(message: Message, target: MessageRecipients, retryId: Long?, sequenceKey: Any, acknowledgementHandler: (() -> Unit)?) {
                val messageData = message.data.deserialize<Any>()

                if (messageData is SessionData && messageData.payload.deserialize() == 1) {
                    val alteredMessageData = SessionData(messageData.recipientSessionId, 99.serialize()).serialize().bytes
                    messagingService.send(InMemoryMessagingNetwork.InMemoryMessage(message.topicSession, alteredMessageData, message.uniqueMessageId), target, retryId)
                } else {
                    messagingService.send(message, target, retryId)
                }
            }
        })
        // DOCEND 1

        val initiatingReceiveFlow = nodeA.services.startFlow(FlowA(nodeB.info.legalIdentities.first()))

        mockNet.runNetwork()

        expectedEx.expect(IllegalArgumentException::class.java)
        expectedEx.expectMessage("Expected to receive 1")
        initiatingReceiveFlow.resultFuture.getOrThrow()
    }
}