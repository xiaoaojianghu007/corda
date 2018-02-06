package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.internal.Emoji
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.node.services.queryBy
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.finance.USD
import net.corda.finance.`issued by`
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.flows.CashIssueFlow
import net.corda.node.internal.SecureCordaRPCOps
import net.corda.node.services.Permissions.Companion.startFlow
import net.corda.testing.contracts.DummyContract
import net.corda.testing.contracts.DummyContractV2
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.MockNetwork
import net.corda.testing.node.MockNode
import net.corda.testing.node.User
import net.corda.testing.node.internal.RPCDriverDSL
import net.corda.testing.node.internal.rpcDriver
import net.corda.testing.node.internal.rpcTestUser
import net.corda.testing.node.internal.startRpcClient
import net.corda.testing.node.startFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractUpgradeFlowTest {
    private lateinit var mockNet: MockNetwork
    private lateinit var aliceNode: MockNode
    private lateinit var bobNode: MockNode
    private lateinit var notary: Party
    private lateinit var alice: Party
    private lateinit var bob: Party

    @Before
    fun setup() {
        mockNet = MockNetwork(cordappPackages = listOf("net.corda.testing.contracts", "net.corda.finance.contracts.asset", "net.corda.core.flows"))
        aliceNode = mockNet.createPartyNode(ALICE_NAME)
        bobNode = mockNet.createPartyNode(BOB_NAME)
        notary = mockNet.defaultNotaryIdentity
        alice = aliceNode.info.singleIdentity()
        bob = bobNode.info.singleIdentity()

        // Process registration
        mockNet.runNetwork()
    }

    @After
    fun tearDown() {
        mockNet.stopNodes()
    }

    @Test
    fun `2 parties contract upgrade`() {
        // Create dummy contract.
        val twoPartyDummyContract = DummyContract.generateInitial(0, notary, alice.ref(1), bob.ref(1))
        val signedByA = aliceNode.services.signInitialTransaction(twoPartyDummyContract)
        val stx = bobNode.services.addSignature(signedByA)

        aliceNode.services.startFlow(FinalityFlow(stx, setOf(bob)))
        mockNet.runNetwork()

        val atx = aliceNode.database.transaction { aliceNode.services.validatedTransactions.getTransaction(stx.id) }
        val btx = bobNode.database.transaction { bobNode.services.validatedTransactions.getTransaction(stx.id) }
        requireNotNull(atx)
        requireNotNull(btx)

        // The request is expected to be rejected because party B hasn't authorised the upgrade yet.
        val rejectedFuture = aliceNode.services.startFlow(ContractUpgradeFlow.Initiate(atx!!.tx.outRef(0), DummyContractV2::class.java)).resultFuture
        mockNet.runNetwork()
        assertFailsWith(UnexpectedFlowEndException::class) { rejectedFuture.getOrThrow() }

        // Party B authorise the contract state upgrade, and immediately deauthorise the same.
        bobNode.services.startFlow(ContractUpgradeFlow.Authorise(btx!!.tx.outRef<ContractState>(0), DummyContractV2::class.java)).resultFuture.getOrThrow()
        bobNode.services.startFlow(ContractUpgradeFlow.Deauthorise(btx.tx.outRef<ContractState>(0).ref)).resultFuture.getOrThrow()

        // The request is expected to be rejected because party B has subsequently deauthorised and a previously authorised upgrade.
        val deauthorisedFuture = aliceNode.services.startFlow(ContractUpgradeFlow.Initiate(atx.tx.outRef(0), DummyContractV2::class.java)).resultFuture
        mockNet.runNetwork()
        assertFailsWith(UnexpectedFlowEndException::class) { deauthorisedFuture.getOrThrow() }

        // Party B authorise the contract state upgrade
        bobNode.services.startFlow(ContractUpgradeFlow.Authorise(btx.tx.outRef<ContractState>(0), DummyContractV2::class.java)).resultFuture.getOrThrow()

        // Party A initiates contract upgrade flow, expected to succeed this time.
        val resultFuture = aliceNode.services.startFlow(ContractUpgradeFlow.Initiate(atx.tx.outRef(0), DummyContractV2::class.java)).resultFuture
        mockNet.runNetwork()

        val result = resultFuture.getOrThrow()

        fun check(node: MockNode) {
            val nodeStx = node.database.transaction {
                node.services.validatedTransactions.getTransaction(result.ref.txhash)
            }
            requireNotNull(nodeStx)

            // Verify inputs.
            val input = node.database.transaction {
                node.services.validatedTransactions.getTransaction(nodeStx!!.tx.inputs.single().txhash)
            }
            requireNotNull(input)
            assertTrue(input!!.tx.outputs.single().data is DummyContract.State)

            // Verify outputs.
            assertTrue(nodeStx!!.tx.outputs.single().data is DummyContractV2.State)
        }
        check(aliceNode)
        check(bobNode)
    }

    private fun RPCDriverDSL.startProxy(node: MockNode, user: User): CordaRPCOps {
        return startRpcClient<CordaRPCOps>(
                rpcAddress = startRpcServer(
                        rpcUser = user,
                        ops = SecureCordaRPCOps(node.services, node.smm, node.database, node.services)
                ).get().broker.hostAndPort!!,
                username = user.username,
                password = user.password
        ).get()
    }

    @Test
    fun `2 parties contract upgrade using RPC`() {
        rpcDriver {
            // Create dummy contract.
            val twoPartyDummyContract = DummyContract.generateInitial(0, notary, alice.ref(1), bob.ref(1))
            val signedByA = aliceNode.services.signInitialTransaction(twoPartyDummyContract)
            val stx = bobNode.services.addSignature(signedByA)

            val user = rpcTestUser.copy(permissions = setOf(
                    startFlow<FinalityInvoker>(),
                    startFlow<ContractUpgradeFlow.Initiate<*, *>>(),
                    startFlow<ContractUpgradeFlow.Authorise>(),
                    startFlow<ContractUpgradeFlow.Deauthorise>()
            ))
            val rpcA = startProxy(aliceNode, user)
            val rpcB = startProxy(bobNode, user)
            val handle = rpcA.startFlow(::FinalityInvoker, stx, setOf(bob))
            mockNet.runNetwork()
            handle.returnValue.getOrThrow()

            val atx = aliceNode.database.transaction { aliceNode.services.validatedTransactions.getTransaction(stx.id) }
            val btx = bobNode.database.transaction { bobNode.services.validatedTransactions.getTransaction(stx.id) }
            requireNotNull(atx)
            requireNotNull(btx)

            val rejectedFuture = rpcA.startFlow({ stateAndRef, upgrade -> ContractUpgradeFlow.Initiate(stateAndRef, upgrade) },
                    atx!!.tx.outRef<DummyContract.State>(0),
                    DummyContractV2::class.java).returnValue

            mockNet.runNetwork()
            assertFailsWith(UnexpectedFlowEndException::class) { rejectedFuture.getOrThrow() }

            // Party B authorise the contract state upgrade, and immediately deauthorise the same.
            rpcB.startFlow({ stateAndRef, upgrade -> ContractUpgradeFlow.Authorise(stateAndRef, upgrade) },
                    btx!!.tx.outRef<ContractState>(0),
                    DummyContractV2::class.java).returnValue
            rpcB.startFlow({ stateRef -> ContractUpgradeFlow.Deauthorise(stateRef) },
                    btx.tx.outRef<ContractState>(0).ref).returnValue

            // The request is expected to be rejected because party B has subsequently deauthorised and a previously authorised upgrade.
            val deauthorisedFuture = rpcA.startFlow({ stateAndRef, upgrade -> ContractUpgradeFlow.Initiate(stateAndRef, upgrade) },
                    atx.tx.outRef<DummyContract.State>(0),
                    DummyContractV2::class.java).returnValue

            mockNet.runNetwork()
            assertFailsWith(UnexpectedFlowEndException::class) { deauthorisedFuture.getOrThrow() }

            // Party B authorise the contract state upgrade.
            rpcB.startFlow({ stateAndRef, upgrade -> ContractUpgradeFlow.Authorise(stateAndRef, upgrade) },
                    btx.tx.outRef<ContractState>(0),
                    DummyContractV2::class.java).returnValue

            // Party A initiates contract upgrade flow, expected to succeed this time.
            val resultFuture = rpcA.startFlow({ stateAndRef, upgrade -> ContractUpgradeFlow.Initiate(stateAndRef, upgrade) },
                    atx.tx.outRef<DummyContract.State>(0),
                    DummyContractV2::class.java).returnValue

            mockNet.runNetwork()
            val result = resultFuture.getOrThrow()
            // Check results.
            listOf(aliceNode, bobNode).forEach {
                val signedTX = aliceNode.database.transaction { aliceNode.services.validatedTransactions.getTransaction(result.ref.txhash) }
                requireNotNull(signedTX)

                // Verify inputs.
                val input = aliceNode.database.transaction { aliceNode.services.validatedTransactions.getTransaction(signedTX!!.tx.inputs.single().txhash) }
                requireNotNull(input)
                assertTrue(input!!.tx.outputs.single().data is DummyContract.State)

                // Verify outputs.
                assertTrue(signedTX!!.tx.outputs.single().data is DummyContractV2.State)
            }
        }
    }

    @Test
    fun `upgrade Cash to v2`() {
        // Create some cash.
        val chosenIdentity = alice
        val result = aliceNode.services.startFlow(CashIssueFlow(Amount(1000, USD), OpaqueBytes.of(1), notary)).resultFuture
        mockNet.runNetwork()
        val stx = result.getOrThrow().stx
        val anonymisedRecipient = result.get().recipient!!
        val stateAndRef = stx.tx.outRef<Cash.State>(0)
        val baseState = aliceNode.database.transaction { aliceNode.services.vaultService.queryBy<ContractState>().states.single() }
        assertTrue(baseState.state.data is Cash.State, "Contract state is old version.")
        // Starts contract upgrade flow.
        val upgradeResult = aliceNode.services.startFlow(ContractUpgradeFlow.Initiate(stateAndRef, CashV2::class.java)).resultFuture
        mockNet.runNetwork()
        upgradeResult.getOrThrow()
        // Get contract state from the vault.
        val firstState = aliceNode.database.transaction { aliceNode.services.vaultService.queryBy<ContractState>().states.single() }
        assertTrue(firstState.state.data is CashV2.State, "Contract state is upgraded to the new version.")
        assertEquals(Amount(1000000, USD).`issued by`(chosenIdentity.ref(1)), (firstState.state.data as CashV2.State).amount, "Upgraded cash contain the correct amount.")
        assertEquals<Collection<AbstractParty>>(listOf(anonymisedRecipient), (firstState.state.data as CashV2.State).owners, "Upgraded cash belongs to the right owner.")
    }

    class CashV2 : UpgradedContract<Cash.State, CashV2.State> {
        override val legacyContract = Cash.PROGRAM_ID

        data class State(override val amount: Amount<Issued<Currency>>, val owners: List<AbstractParty>) : FungibleAsset<Currency> {
            override val owner: AbstractParty = owners.first()
            override val exitKeys = (owners + amount.token.issuer.party).map { it.owningKey }.toSet()
            override val participants = owners

            override fun withNewOwnerAndAmount(newAmount: Amount<Issued<Currency>>, newOwner: AbstractParty) = copy(amount = amount.copy(newAmount.quantity), owners = listOf(newOwner))
            override fun toString() = "${Emoji.bagOfCash}New Cash($amount at ${amount.token.issuer} owned by $owner)"
            override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(Cash.Commands.Move(), copy(owners = listOf(newOwner)))
        }

        override fun upgrade(state: Cash.State) = CashV2.State(state.amount.times(1000), listOf(state.owner))

        override fun verify(tx: LedgerTransaction) {}
    }

    @StartableByRPC
    class FinalityInvoker(private val transaction: SignedTransaction,
                          private val extraRecipients: Set<Party>) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction = subFlow(FinalityFlow(transaction, extraRecipients))
    }
}
