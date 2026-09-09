package io.github.matthewjones372.proofload.grpc.dynamic

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors.DescriptorValidationException
import com.google.protobuf.Descriptors.FileDescriptor
import com.google.protobuf.Descriptors.MethodDescriptor
import com.google.protobuf.InvalidProtocolBufferException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The methods a service declares, without a generated stub for any of them.
 *
 * Keyed by the name gRPC puts on the wire — `package.Service/Method` — because
 * that is what a plan writes, what `grpcurl` takes, and what every log line
 * about the call already says.
 */
class Schema internal constructor(private val declared: Map<String, MethodDescriptor>) {

    /** Every method here, sorted, which is what a mistyped name is answered with. */
    val methods: List<String> get() = declared.keys.sorted()

    /** The method [name] declares, or a refusal naming the ones there are. */
    fun method(name: String): MethodDescriptor = declared[name] ?: throw IllegalArgumentException(
        "no method `$name` here. This descriptor set declares ${methods.joinToString { "`$it`" }}",
    )
}

/**
 * The schema in a descriptor set, as `protoc --descriptor_set_out` writes one.
 *
 * `--include_imports` matters and its absence is the common mistake, so a file
 * importing one that is not in the set is refused here naming both rather than
 * at the first call naming a type.
 */
fun descriptorSet(bytes: ByteArray): Schema {
    val set = try {
        FileDescriptorSet.parseFrom(bytes)
    } catch (notASet: InvalidProtocolBufferException) {
        throw IllegalArgumentException(
            "this is not a descriptor set: ${notASet.message}. " +
                "Write one with `protoc --descriptor_set_out=shop.protoset --include_imports shop.proto`",
            notASet,
        )
    }

    val linked = set.fileList.linked()
    return Schema(
        linked.values
            .flatMap { file -> file.services }
            .flatMap { service -> service.methods.map { "${service.fullName}/${it.name}" to it } }
            .toMap(),
    )
}

/** The same, from a file. */
fun descriptorSet(path: Path): Schema = try {
    descriptorSet(Files.readAllBytes(path))
} catch (notASet: IllegalArgumentException) {
    throw IllegalArgumentException("$path: ${notASet.message}", notASet)
}

/**
 * Every file linked against the ones it imports.
 *
 * Protobuf will not build a `FileDescriptor` without its dependencies already
 * built, so the set is walked depth-first from each file. A set is small and a
 * cycle is impossible in one protoc wrote, so this is a fold over a map rather
 * than a topological sort with a phase of its own.
 */
private fun List<FileDescriptorProto>.linked(): Map<String, FileDescriptor> {
    val byName = associateBy { it.name }
    // Built as it goes so a file imported by three others is linked once: the
    // accumulator is the memo, and it is local to this call.
    val built = mutableMapOf<String, FileDescriptor>()

    fun link(proto: FileDescriptorProto): FileDescriptor = built.getOrPut(proto.name) {
        val imports = proto.dependencyList.map { dependency ->
            val imported = requireNotNull(byName[dependency]) {
                "`${proto.name}` imports `$dependency`, which is not in this descriptor set. " +
                    "Write it with `protoc --descriptor_set_out=... --include_imports`"
            }
            link(imported)
        }

        try {
            FileDescriptor.buildFrom(proto, imports.toTypedArray())
        } catch (unresolved: DescriptorValidationException) {
            throw IllegalArgumentException("`${proto.name}` does not resolve: ${unresolved.message}", unresolved)
        }
    }

    return associate { it.name to link(it) }
}
