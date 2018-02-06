package net.corda.client.rpc.internal

import com.esotericsoftware.kryo.pool.KryoPool
import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.internal.CordaSerializationMagic
import net.corda.core.serialization.internal.SerializationEnvironment
import net.corda.core.serialization.internal.SerializationEnvironmentImpl
import net.corda.core.serialization.internal.nodeSerializationEnv
import net.corda.nodeapi.internal.serialization.KRYO_P2P_CONTEXT
import net.corda.nodeapi.internal.serialization.KRYO_RPC_CLIENT_CONTEXT
import net.corda.nodeapi.internal.serialization.SerializationFactoryImpl
import net.corda.nodeapi.internal.serialization.amqp.AMQPClientSerializationScheme
import net.corda.nodeapi.internal.serialization.kryo.AbstractKryoSerializationScheme
import net.corda.nodeapi.internal.serialization.kryo.DefaultKryoCustomizer
import net.corda.nodeapi.internal.serialization.kryo.kryoMagic
import net.corda.nodeapi.internal.serialization.kryo.RPCKryo

class KryoClientSerializationScheme : AbstractKryoSerializationScheme() {
    override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean {
        return magic == kryoMagic && (target == SerializationContext.UseCase.RPCClient || target == SerializationContext.UseCase.P2P)
    }

    override fun rpcClientKryoPool(context: SerializationContext): KryoPool {
        return KryoPool.Builder {
            DefaultKryoCustomizer.customize(RPCKryo(RpcClientObservableSerializer, context), publicKeySerializer).apply {
                classLoader = context.deserializationClassLoader
            }
        }.build()
    }

    // We're on the client and don't have access to server classes.
    override fun rpcServerKryoPool(context: SerializationContext): KryoPool = throw UnsupportedOperationException()

    companion object {
        /** Call from main only. */
        fun initialiseSerialization() {
            nodeSerializationEnv = createSerializationEnv()
        }

        fun createSerializationEnv(): SerializationEnvironment {
            return SerializationEnvironmentImpl(
                    SerializationFactoryImpl().apply {
                        registerScheme(KryoClientSerializationScheme())
                        registerScheme(AMQPClientSerializationScheme(emptyList()))
                    },
                    KRYO_P2P_CONTEXT,
                    rpcClientContext = KRYO_RPC_CLIENT_CONTEXT)
        }
    }
}