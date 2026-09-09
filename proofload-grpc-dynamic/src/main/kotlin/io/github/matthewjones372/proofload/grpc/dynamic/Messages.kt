package io.github.matthewjones372.proofload.grpc.dynamic

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.DynamicMessage
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat

/**
 * The JSON a plan wrote, as the message this type declares.
 *
 * Strict about fields nobody declared, which is the opposite of protobuf's
 * usual generosity and is the point: a plan buys convenience with the guarantee
 * 0071 was written for, so a renamed field is as loud as a file can make it
 * rather than a key that arrives and is quietly dropped.
 *
 * Merged a field at a time rather than in one call, so a refusal names the key
 * that caused it. Protobuf's own message for a bad value says what it wanted
 * and not where — "Not an int32 value" is unanswerable in a document with
 * thirty keys in it. Reading the object twice costs nothing here: this runs
 * once when a plan is lowered, never on a departure.
 */
fun Descriptor.messageFrom(json: String): DynamicMessage {
    val fields = json.asObject(this)
    val message = DynamicMessage.newBuilder(this)

    fields.forEach { (name, value) ->
        try {
            JsonFormat.parser().merge("{${name.quoted()}:${value.written()}}", message)
        } catch (wrong: InvalidProtocolBufferException) {
            throw IllegalArgumentException("`$name` is not a $fullName field this reads: ${wrong.message}", wrong)
        }
    }

    return message.build()
}

/**
 * This message as one line of JSON.
 *
 * One line rather than protobuf's own text format, because what reads this is a
 * `trace` printing what came back beside what went out, and because it is the
 * shape the plan wrote the request in.
 */
fun Message.asJson(): String = JsonFormat.printer().omittingInsignificantWhitespace().print(this)

/**
 * The JSON object's own fields, read through `Struct`.
 *
 * Protobuf's own well-known type for "some JSON", so this needs no parser of
 * its own beside the one `protobuf-java-util` already carries — and a document
 * that is not an object at all fails here, where the type it was meant to be
 * can still be named.
 */
private fun String.asObject(type: Descriptor): Map<String, Value> {
    val fields = Struct.newBuilder()
    try {
        JsonFormat.parser().merge(this, fields)
    } catch (notAnObject: InvalidProtocolBufferException) {
        throw IllegalArgumentException("this is not a ${type.fullName}: ${notAnObject.message}", notAnObject)
    }
    return fields.build().fieldsMap
}

private fun Value.written(): String = JsonFormat.printer().omittingInsignificantWhitespace().print(this)

private fun String.quoted(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
