package net.corda.client.rpc.internal

import net.corda.client.rpc.RPCConnection
import net.corda.client.rpc.RPCException
import net.corda.core.context.Actor
import net.corda.core.context.Trace
import net.corda.core.crypto.random63BitValue
import net.corda.core.internal.logElapsedTime
import net.corda.core.internal.uncheckedCast
import net.corda.core.messaging.RPCOps
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationDefaults
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.core.utilities.*
import net.corda.nodeapi.ArtemisTcpTransport.Companion.tcpTransport
import net.corda.nodeapi.ConnectionDirection
import net.corda.nodeapi.RPCApi
import net.corda.nodeapi.internal.config.SSLConfiguration
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.TransportConfiguration
import org.apache.activemq.artemis.api.core.client.ActiveMQClient
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * This configuration may be used to tweak the internals of the RPC client.
 */
data class RPCClientConfiguration(
        /** The minimum protocol version required from the server */
        val minimumServerProtocolVersion: Int,
        /**
         * If set to true the client will track RPC call sites. If an error occurs subsequently during the RPC or in a
         * returned Observable stream the stack trace of the originating RPC will be shown as well. Note that
         * constructing call stacks is a moderately expensive operation.
         */
        val trackRpcCallSites: Boolean,
        /**
         * The interval of unused observable reaping. Leaked Observables (unused ones) are detected using weak references
         * and are cleaned up in batches in this interval. If set too large it will waste server side resources for this
         * duration. If set too low it wastes client side cycles.
         */
        val reapInterval: Duration,
        /** The number of threads to use for observations (for executing [Observable.onNext]) */
        val observationExecutorPoolSize: Int,
        /** The maximum number of producers to create to handle outgoing messages */
        val producerPoolBound: Int,
        /**
         * Determines the concurrency level of the Observable Cache. This is exposed because it implicitly determines
         * the limit on the number of leaked observables reaped because of garbage collection per reaping.
         * See the implementation of [com.google.common.cache.LocalCache] for details.
         */
        val cacheConcurrencyLevel: Int,
        /** The retry interval of artemis connections in milliseconds */
        val connectionRetryInterval: Duration,
        /** The retry interval multiplier for exponential backoff */
        val connectionRetryIntervalMultiplier: Double,
        /** Maximum retry interval */
        val connectionMaxRetryInterval: Duration,
        /** Maximum reconnect attempts on failover */
        val maxReconnectAttempts: Int,
        /** Maximum file size */
        val maxFileSize: Int,
        /** The size of the LRU cache used to deduplicate messages coming from the server */
        val deduplicationMessageIdCacheSize: Long
) {
    companion object {
        val unlimitedReconnectAttempts = -1
        @JvmStatic
        val default = RPCClientConfiguration(
                minimumServerProtocolVersion = 0,
                trackRpcCallSites = false,
                reapInterval = 1.seconds,
                observationExecutorPoolSize = 4,
                producerPoolBound = 1,
                cacheConcurrencyLevel = 8,
                connectionRetryInterval = 5.seconds,
                connectionRetryIntervalMultiplier = 1.5,
                connectionMaxRetryInterval = 3.minutes,
                maxReconnectAttempts = unlimitedReconnectAttempts,
                /** 10 MiB maximum allowed file size for attachments, including message headers. TODO: acquire this value from Network Map when supported. */
                maxFileSize = 10485760,
                deduplicationMessageIdCacheSize = 2048
        )
    }
}

class RPCClient<I : RPCOps>(
        val transport: TransportConfiguration,
        val rpcConfiguration: RPCClientConfiguration = RPCClientConfiguration.default,
        val serializationContext: SerializationContext = SerializationDefaults.RPC_CLIENT_CONTEXT
) {
    constructor(
            hostAndPort: NetworkHostAndPort,
            sslConfiguration: SSLConfiguration? = null,
            configuration: RPCClientConfiguration = RPCClientConfiguration.default,
            serializationContext: SerializationContext = SerializationDefaults.RPC_CLIENT_CONTEXT
    ) : this(tcpTransport(ConnectionDirection.Outbound(), hostAndPort, sslConfiguration), configuration, serializationContext)

    companion object {
        private val log = contextLogger()
    }

    fun start(
            rpcOpsClass: Class<I>,
            username: String,
            password: String,
            externalTrace: Trace? = null,
            impersonatedActor: Actor? = null
    ): RPCConnection<I> {
        return log.logElapsedTime("Startup") {
            val clientAddress = SimpleString("${RPCApi.RPC_CLIENT_QUEUE_NAME_PREFIX}.$username.${random63BitValue()}")

            val serverLocator = ActiveMQClient.createServerLocatorWithoutHA(transport).apply {
                retryInterval = rpcConfiguration.connectionRetryInterval.toMillis()
                retryIntervalMultiplier = rpcConfiguration.connectionRetryIntervalMultiplier
                maxRetryInterval = rpcConfiguration.connectionMaxRetryInterval.toMillis()
                reconnectAttempts = rpcConfiguration.maxReconnectAttempts
                minLargeMessageSize = rpcConfiguration.maxFileSize
                isUseGlobalPools = nodeSerializationEnv != null
            }
            val sessionId = Trace.SessionId.newInstance()
            val proxyHandler = RPCClientProxyHandler(rpcConfiguration, username, password, serverLocator, clientAddress, rpcOpsClass, serializationContext, sessionId, externalTrace, impersonatedActor)
            try {
                proxyHandler.start()
                val ops: I = uncheckedCast(Proxy.newProxyInstance(rpcOpsClass.classLoader, arrayOf(rpcOpsClass), proxyHandler))
                val serverProtocolVersion = ops.protocolVersion
                if (serverProtocolVersion < rpcConfiguration.minimumServerProtocolVersion) {
                    throw RPCException("Requested minimum protocol version (${rpcConfiguration.minimumServerProtocolVersion}) is higher" +
                            " than the server's supported protocol version ($serverProtocolVersion)")
                }
                proxyHandler.setServerProtocolVersion(serverProtocolVersion)

                log.debug("RPC connected, returning proxy")
                object : RPCConnection<I> {
                    override val proxy = ops
                    override val serverProtocolVersion = serverProtocolVersion

                    private fun close(notify: Boolean) {
                        if (notify) {
                            proxyHandler.notifyServerAndClose()
                        } else {
                            proxyHandler.forceClose()
                        }
                        serverLocator.close()
                    }

                    override fun notifyServerAndClose() {
                        close(true)
                    }

                    override fun forceClose() {
                        close(false)
                    }

                    override fun close() {
                        close(true)
                    }
                }
            } catch (exception: Throwable) {
                proxyHandler.notifyServerAndClose()
                serverLocator.close()
                throw exception
            }
        }
    }
}
