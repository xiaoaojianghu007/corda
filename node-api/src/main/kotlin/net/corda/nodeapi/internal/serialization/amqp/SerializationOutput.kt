package net.corda.nodeapi.internal.serialization.amqp

import net.corda.core.serialization.SerializedBytes
import org.apache.qpid.proton.codec.Data
import java.io.NotSerializableException
import java.lang.reflect.Type
import java.nio.ByteBuffer
import java.util.*
import kotlin.collections.LinkedHashSet

data class BytesAndSchemas<T : Any>(
        val obj: SerializedBytes<T>,
        val schema: Schema,
        val transformsSchema: TransformsSchema)

/**
 * Main entry point for serializing an object to AMQP.
 *
 * @param serializerFactory This is the factory for [AMQPSerializer] instances and can be shared across multiple
 * instances and threads.
 */
open class SerializationOutput(internal val serializerFactory: SerializerFactory) {

    private val objectHistory: MutableMap<Any, Int> = IdentityHashMap()
    private val serializerHistory: MutableSet<AMQPSerializer<*>> = LinkedHashSet()
    internal val schemaHistory: MutableSet<TypeNotation> = LinkedHashSet()

    /**
     * Serialize the given object to AMQP, wrapped in our [Envelope] wrapper which carries an AMQP 1.0 schema, and prefixed
     * with a header to indicate that this is serialized with AMQP and not Kryo, and what version of the Corda implementation
     * of AMQP serialization constructed the serialized form.
     */
    @Throws(NotSerializableException::class)
    fun <T : Any> serialize(obj: T): SerializedBytes<T> {
        try {
            return _serialize(obj)
        } finally {
            andFinally()
        }
    }


    @Throws(NotSerializableException::class)
    fun <T : Any> serializeAndReturnSchema(obj: T): BytesAndSchemas<T> {
        try {
            val blob = _serialize(obj)
            val schema = Schema(schemaHistory.toList())
            return BytesAndSchemas(blob, schema, TransformsSchema.build(schema, serializerFactory))
        } finally {
            andFinally()
        }
    }

    internal fun andFinally() {
        objectHistory.clear()
        serializerHistory.clear()
        schemaHistory.clear()
    }

    internal fun <T : Any> _serialize(obj: T): SerializedBytes<T> {
        val data = Data.Factory.create()
        data.withDescribed(Envelope.DESCRIPTOR_OBJECT) {
            withList {
                writeObject(obj, this)
                val schema = Schema(schemaHistory.toList())
                writeSchema(schema, this)
                writeTransformSchema(TransformsSchema.build(schema, serializerFactory), this)
            }
        }
        val bytes = ByteArray(data.encodedSize().toInt() + 8)
        val buf = ByteBuffer.wrap(bytes)
        amqpMagic.putTo(buf)
        data.encode(buf)
        return SerializedBytes(bytes)
    }

    internal fun writeObject(obj: Any, data: Data) {
        writeObject(obj, data, obj.javaClass)
    }

    open fun writeSchema(schema: Schema, data: Data) {
        data.putObject(schema)
    }

    open fun writeTransformSchema(transformsSchema: TransformsSchema, data: Data) {
        data.putObject(transformsSchema)
    }

    internal fun writeObjectOrNull(obj: Any?, data: Data, type: Type, debugIndent: Int) {
        if (obj == null) {
            data.putNull()
        } else {
            writeObject(obj, data, if (type == SerializerFactory.AnyType) obj.javaClass else type, debugIndent)
        }
    }

    internal fun writeObject(obj: Any, data: Data, type: Type, debugIndent: Int = 0) {
        val serializer = serializerFactory.get(obj.javaClass, type)
        if (serializer !in serializerHistory) {
            serializerHistory.add(serializer)
            serializer.writeClassInfo(this)
        }

        val retrievedRefCount = objectHistory[obj]
        if (retrievedRefCount == null) {
            serializer.writeObject(obj, data, type, this, debugIndent)
            // Important to do it after serialization such that dependent object will have preceding reference numbers
            // assigned to them first as they will be first read from the stream on receiving end.
            // Skip for primitive types as they are too small and overhead of referencing them will be much higher than their content
            if (suitableForObjectReference(obj.javaClass)) {
                objectHistory.put(obj, objectHistory.size)
            }
        } else {
            data.writeReferencedObject(ReferencedObject(retrievedRefCount))
        }
    }

    open internal fun writeTypeNotations(vararg typeNotation: TypeNotation): Boolean {
        return schemaHistory.addAll(typeNotation)
    }

    open internal fun requireSerializer(type: Type) {
        if (type != SerializerFactory.AnyType && type != Object::class.java) {
            val serializer = serializerFactory.get(null, type)
            if (serializer !in serializerHistory) {
                serializerHistory.add(serializer)
                serializer.writeClassInfo(this)
            }
        }
    }
}

