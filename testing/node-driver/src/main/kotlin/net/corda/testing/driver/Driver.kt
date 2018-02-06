@file:JvmName("Driver")

package net.corda.testing.driver

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.DoNotImplement
import net.corda.core.concurrent.CordaFuture
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.NodeInfo
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.node.internal.Node
import net.corda.node.internal.StartedNode
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.VerifierType
import net.corda.nodeapi.internal.config.SSLConfiguration
import net.corda.testing.core.DUMMY_NOTARY_NAME
import net.corda.testing.node.NotarySpec
import net.corda.testing.node.User
import net.corda.testing.node.internal.DriverDSLImpl
import net.corda.testing.node.internal.genericDriver
import net.corda.testing.node.internal.getTimestampAsDirectoryName
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicInteger

/**
 * Object ecapsulating a notary started automatically by the driver.
 */
data class NotaryHandle(val identity: Party, val validating: Boolean, val nodeHandles: CordaFuture<List<NodeHandle>>)

@DoNotImplement
sealed class NodeHandle : AutoCloseable {
    abstract val nodeInfo: NodeInfo
    /**
     * Interface to the node's RPC system. The first RPC user will be used to login if are any, otherwise a default one
     * will be added and that will be used.
     */
    abstract val rpc: CordaRPCOps
    abstract val configuration: NodeConfiguration
    abstract val webAddress: NetworkHostAndPort
    abstract val useHTTPS: Boolean

    /**
     * Stops the referenced node.
     */
    abstract fun stop()

    /**
     * Closes and stops the node.
     */
    override fun close() = stop()

    data class OutOfProcess(
            override val nodeInfo: NodeInfo,
            override val rpc: CordaRPCOps,
            override val configuration: NodeConfiguration,
            override val webAddress: NetworkHostAndPort,
            override val useHTTPS: Boolean,
            val debugPort: Int?,
            val process: Process,
            private val onStopCallback: () -> Unit
    ) : NodeHandle() {
        override fun stop() {
            with(process) {
                destroy()
                waitFor()
            }
            onStopCallback()
        }
    }

    data class InProcess(
            override val nodeInfo: NodeInfo,
            override val rpc: CordaRPCOps,
            override val configuration: NodeConfiguration,
            override val webAddress: NetworkHostAndPort,
            override val useHTTPS: Boolean,
            val nodeThread: Thread,
            private val onStopCallback: () -> Unit
    ) : NodeHandle() {
        override fun stop() {
            node.dispose()
            with(nodeThread) {
                interrupt()
                join()
            }
            onStopCallback()
        }
        private lateinit var node: StartedNode<Node>
        val database get() = node.database
        val services get() = node.services
        fun <T : FlowLogic<*>> registerInitiatedFlow(initiatedFlowClass: Class<T>) = node.registerInitiatedFlow(initiatedFlowClass)
        internal fun initialise(node: StartedNode<Node>) : InProcess {
            this.node = node
            return this
        }
    }

    /**
     * Connects to node through RPC.
     *
     * @param sslConfiguration specifies SSL options.
     */
    @JvmOverloads
    fun rpcClientToNode(sslConfiguration: SSLConfiguration? = null): CordaRPCClient = CordaRPCClient(configuration.rpcOptions.address!!, sslConfiguration = sslConfiguration)
}

data class WebserverHandle(
        val listenAddress: NetworkHostAndPort,
        val process: Process
)

@DoNotImplement
sealed class PortAllocation {
    abstract fun nextPort(): Int
    fun nextHostAndPort() = NetworkHostAndPort("localhost", nextPort())

    class Incremental(startingPort: Int) : PortAllocation() {
        val portCounter = AtomicInteger(startingPort)
        override fun nextPort() = portCounter.andIncrement
    }

    object RandomFree : PortAllocation() {
        override fun nextPort(): Int {
            return ServerSocket().use {
                it.bind(InetSocketAddress(0))
                it.localPort
            }
        }
    }
}

/** Helper builder for configuring a [Node] from Java. */
@Suppress("unused")
data class NodeParameters(
        val providedName: CordaX500Name? = null,
        val rpcUsers: List<User> = emptyList(),
        val verifierType: VerifierType = VerifierType.InMemory,
        val customOverrides: Map<String, Any?> = emptyMap(),
        val startInSameProcess: Boolean? = null,
        val maximumHeapSize: String = "200m"
) {
    fun setProvidedName(providedName: CordaX500Name?) = copy(providedName = providedName)
    fun setRpcUsers(rpcUsers: List<User>) = copy(rpcUsers = rpcUsers)
    fun setVerifierType(verifierType: VerifierType) = copy(verifierType = verifierType)
    fun setCustomerOverrides(customOverrides: Map<String, Any?>) = copy(customOverrides = customOverrides)
    fun setStartInSameProcess(startInSameProcess: Boolean?) = copy(startInSameProcess = startInSameProcess)
    fun setMaximumHeapSize(maximumHeapSize: String) = copy(maximumHeapSize = maximumHeapSize)
}

data class JmxPolicy(val startJmxHttpServer: Boolean = false,
                     val jmxHttpServerPortAllocation: PortAllocation? =
                        if (startJmxHttpServer) PortAllocation.Incremental(7005) else null)

/**
 * [driver] allows one to start up nodes like this:
 *   driver {
 *     val noService = startNode(providedName = DUMMY_BANK_A.name)
 *     val notary = startNode(providedName = DUMMY_NOTARY.name)
 *
 *     (...)
 *   }
 *
 * Note that [DriverDSL.startNode] does not wait for the node to start up synchronously, but rather returns a [CordaFuture]
 * of the [NodeInfo] that may be waited on, which completes when the new node registered with the network map service or
 * loaded node data from database.
 *
 * @param defaultParameters The default parameters for the driver. Allows the driver to be configured in builder style
 *   when called from Java code.
 * @param isDebug Indicates whether the spawned nodes should start in jdwt debug mode and have debug level logging.
 * @param driverDirectory The base directory node directories go into, defaults to "build/<timestamp>/". The node
 *   directories themselves are "<baseDirectory>/<legalName>/", where legalName defaults to "<randomName>-<messagingPort>"
 *   and may be specified in [DriverDSL.startNode].
 * @param portAllocation The port allocation strategy to use for the messaging and the web server addresses. Defaults to incremental.
 * @param debugPortAllocation The port allocation strategy to use for jvm debugging. Defaults to incremental.
 * @param systemProperties A Map of extra system properties which will be given to each new node. Defaults to empty.
 * @param useTestClock If true the test clock will be used in Node.
 * @param startNodesInProcess Provides the default behaviour of whether new nodes should start inside this process or
 *     not. Note that this may be overridden in [DriverDSL.startNode].
 * @param waitForAllNodesToFinish If true, the nodes will not shut down automatically after executing the code in the driver DSL block. 
 *     It will wait for them to be shut down externally instead.
 * @param notarySpecs The notaries advertised for this network. These nodes will be started automatically and will be
 * available from [DriverDSL.notaryHandles]. Defaults to a simple validating notary.
 * @param jmxPolicy Used to specify whether to expose JMX metrics via Jolokia HHTP/JSON. Defines two attributes:
 *      startJmxHttpServer: indicates whether the spawned nodes should start with a Jolokia JMX agent to enable remote JMX monitoring using HTTP/JSON.
 *      jmxHttpServerPortAllocation: the port allocation strategy to use for remote Jolokia/JMX monitoring over HTTP. Defaults to incremental.
 * @param dsl The dsl itself.
 * @return The value returned in the [dsl] closure.
 */
fun <A> driver(
        defaultParameters: DriverParameters = DriverParameters(),
        isDebug: Boolean = defaultParameters.isDebug,
        driverDirectory: Path = defaultParameters.driverDirectory,
        portAllocation: PortAllocation = defaultParameters.portAllocation,
        debugPortAllocation: PortAllocation = defaultParameters.debugPortAllocation,
        systemProperties: Map<String, String> = defaultParameters.systemProperties,
        useTestClock: Boolean = defaultParameters.useTestClock,
        initialiseSerialization: Boolean = defaultParameters.initialiseSerialization,
        startNodesInProcess: Boolean = defaultParameters.startNodesInProcess,
        waitForAllNodesToFinish: Boolean = defaultParameters.waitForAllNodesToFinish,
        notarySpecs: List<NotarySpec> = defaultParameters.notarySpecs,
        extraCordappPackagesToScan: List<String> = defaultParameters.extraCordappPackagesToScan,
        jmxPolicy: JmxPolicy = defaultParameters.jmxPolicy,
        dsl: DriverDSL.() -> A
): A {
    return genericDriver(
            driverDsl = DriverDSLImpl(
                    portAllocation = portAllocation,
                    debugPortAllocation = debugPortAllocation,
                    systemProperties = systemProperties,
                    driverDirectory = driverDirectory.toAbsolutePath(),
                    useTestClock = useTestClock,
                    isDebug = isDebug,
                    startNodesInProcess = startNodesInProcess,
                    waitForNodesToFinish = waitForAllNodesToFinish,
                    notarySpecs = notarySpecs,
                    extraCordappPackagesToScan = extraCordappPackagesToScan,
                    jmxPolicy = jmxPolicy,
                    compatibilityZone = null
            ),
            coerce = { it },
            dsl = dsl,
            initialiseSerialization = initialiseSerialization
    )
}

/**
 * Helper function for starting a [driver] with custom parameters from Java.
 *
 * @param parameters The default parameters for the driver.
 * @param dsl The dsl itself.
 * @return The value returned in the [dsl] closure.
 */
fun <A> driver(
        parameters: DriverParameters,
        dsl: DriverDSL.() -> A
): A {
    return driver(defaultParameters = parameters, dsl = dsl)
}

/** Helper builder for configuring a [driver] from Java. */
@Suppress("unused")
data class DriverParameters(
        val isDebug: Boolean = false,
        val driverDirectory: Path = Paths.get("build", getTimestampAsDirectoryName()),
        val portAllocation: PortAllocation = PortAllocation.Incremental(10000),
        val debugPortAllocation: PortAllocation = PortAllocation.Incremental(5005),
        val systemProperties: Map<String, String> = emptyMap(),
        val useTestClock: Boolean = false,
        val initialiseSerialization: Boolean = true,
        val startNodesInProcess: Boolean = false,
        val waitForAllNodesToFinish: Boolean = false,
        val notarySpecs: List<NotarySpec> = listOf(NotarySpec(DUMMY_NOTARY_NAME)),
        val extraCordappPackagesToScan: List<String> = emptyList(),
        val jmxPolicy: JmxPolicy = JmxPolicy()
) {
    fun setIsDebug(isDebug: Boolean) = copy(isDebug = isDebug)
    fun setDriverDirectory(driverDirectory: Path) = copy(driverDirectory = driverDirectory)
    fun setPortAllocation(portAllocation: PortAllocation) = copy(portAllocation = portAllocation)
    fun setDebugPortAllocation(debugPortAllocation: PortAllocation) = copy(debugPortAllocation = debugPortAllocation)
    fun setSystemProperties(systemProperties: Map<String, String>) = copy(systemProperties = systemProperties)
    fun setUseTestClock(useTestClock: Boolean) = copy(useTestClock = useTestClock)
    fun setInitialiseSerialization(initialiseSerialization: Boolean) = copy(initialiseSerialization = initialiseSerialization)
    fun setStartNodesInProcess(startNodesInProcess: Boolean) = copy(startNodesInProcess = startNodesInProcess)
    fun setWaitForAllNodesToFinish(waitForAllNodesToFinish: Boolean) = copy(waitForAllNodesToFinish = waitForAllNodesToFinish)
    fun setNotarySpecs(notarySpecs: List<NotarySpec>) = copy(notarySpecs = notarySpecs)
    fun setExtraCordappPackagesToScan(extraCordappPackagesToScan: List<String>) = copy(extraCordappPackagesToScan = extraCordappPackagesToScan)
    fun setJmxPolicy(jmxPolicy: JmxPolicy) = copy(jmxPolicy = jmxPolicy)
}
