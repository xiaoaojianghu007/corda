@file:JvmName("SharedContexts")

package net.corda.nodeapi.internal.serialization

import net.corda.core.serialization.SerializationContext
import net.corda.core.serialization.SerializationDefaults
import net.corda.nodeapi.internal.serialization.amqp.amqpMagic
import net.corda.nodeapi.internal.serialization.kryo.kryoMagic

/*
 * Serialisation contexts shared by the server and client.
 *
 * NOTE: The [KRYO_STORAGE_CONTEXT] and [AMQP_STORAGE_CONTEXT]
 * CANNOT always be instantiated outside of the server and so
 * MUST be kept separate from these ones!
 */

val KRYO_P2P_CONTEXT = SerializationContextImpl(kryoMagic,
        SerializationDefaults.javaClass.classLoader,
        GlobalTransientClassWhiteList(BuiltInExceptionsWhitelist()),
        emptyMap(),
        true,
        SerializationContext.UseCase.P2P,
        null)
val KRYO_CHECKPOINT_CONTEXT = SerializationContextImpl(kryoMagic,
        SerializationDefaults.javaClass.classLoader,
        QuasarWhitelist,
        emptyMap(),
        true,
        SerializationContext.UseCase.Checkpoint,
        null)
val AMQP_P2P_CONTEXT = SerializationContextImpl(amqpMagic,
        SerializationDefaults.javaClass.classLoader,
        GlobalTransientClassWhiteList(BuiltInExceptionsWhitelist()),
        emptyMap(),
        true,
        SerializationContext.UseCase.P2P,
        null)
