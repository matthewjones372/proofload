package io.github.matthewjones372.kestrel.grpc.dynamic

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto

/**
 * The bytes `protoc --descriptor_set_out` writes, built here instead.
 *
 * The same message type either way — a `FileDescriptorSet` is a protobuf
 * message like any other — so this is the real input rather than a stand-in.
 * Running `protoc` from the build would need it installed on every machine and
 * in CI to prove something about a parser rather than about a compiler.
 */
internal object Shop {

    /** `shop.Orders`, with a `PlaceOrder` and a `GetOrder`, in one file. */
    val descriptorSet: ByteArray by lazy { setOf(orders()).toByteArray() }

    /** The same service, split so one file imports another: what `--include_imports` produces. */
    val acrossFiles: ByteArray by lazy { setOf(types(), service()).toByteArray() }

    /** [acrossFiles] with the imported file left out, which is what forgetting `--include_imports` gives. */
    val missingImport: ByteArray by lazy { setOf(service()).toByteArray() }

    private fun setOf(vararg files: FileDescriptorProto): FileDescriptorSet =
        FileDescriptorSet.newBuilder().addAllFile(files.toList()).build()

    private fun orders(): FileDescriptorProto = FileDescriptorProto.newBuilder()
        .setName("shop/orders.proto")
        .setPackage("shop")
        .setSyntax("proto3")
        .addMessageType(order())
        .addMessageType(confirmation())
        .addService(
            ServiceDescriptorProto.newBuilder()
                .setName("Orders")
                .addMethod(unary("PlaceOrder", ".shop.Order", ".shop.Confirmation"))
                .addMethod(unary("GetOrder", ".shop.Order", ".shop.Confirmation")),
        )
        .build()

    private fun types(): FileDescriptorProto = FileDescriptorProto.newBuilder()
        .setName("shop/types.proto")
        .setPackage("shop")
        .setSyntax("proto3")
        .addMessageType(order())
        .addMessageType(confirmation())
        .build()

    private fun service(): FileDescriptorProto = FileDescriptorProto.newBuilder()
        .setName("shop/service.proto")
        .setPackage("shop")
        .setSyntax("proto3")
        .addDependency("shop/types.proto")
        .addService(
            ServiceDescriptorProto.newBuilder()
                .setName("Orders")
                .addMethod(unary("PlaceOrder", ".shop.Order", ".shop.Confirmation")),
        )
        .build()

    private fun unary(name: String, takes: String, gives: String): MethodDescriptorProto =
        MethodDescriptorProto.newBuilder()
            .setName(name)
            .setInputType(takes)
            .setOutputType(gives)
            .build()

    private fun order(): DescriptorProto = DescriptorProto.newBuilder()
        .setName("Order")
        .addField(field("cart", 1, FieldDescriptorProto.Type.TYPE_STRING))
        .addField(field("quantity", 2, FieldDescriptorProto.Type.TYPE_INT32))
        .build()

    private fun confirmation(): DescriptorProto = DescriptorProto.newBuilder()
        .setName("Confirmation")
        .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
        .build()

    private fun field(name: String, number: Int, type: FieldDescriptorProto.Type): FieldDescriptorProto =
        FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setType(type)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .build()
}
