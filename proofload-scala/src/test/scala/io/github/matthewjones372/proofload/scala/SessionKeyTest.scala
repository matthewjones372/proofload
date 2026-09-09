package io.github.matthewjones372.proofload.scala

import io.github.matthewjones372.proofload.SessionKt
import io.github.matthewjones372.proofload.java.SessionKeys
import _root_.kotlin.jvm.JvmClassMappingKt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The one place Scala does better than Java: `ClassTag` recovers at compile
 * time the type Java has to be handed at runtime.
 */
class SessionKeyTest:

  @Test
  def `a key names the type its parameter says, not one passed beside it`(): Unit =
    assertEquals(SessionKeys.of(classOf[String], "orderId"), sessionKey[String]("orderId"))

  @Test
  def `the key is the one the Kotlin DSL declares for the same type and name`(): Unit =
    val fromKotlin = SessionKt.sessionKey("orderId", JvmClassMappingKt.getKotlinClass(classOf[String]))

    assertEquals(fromKotlin, sessionKey[String]("orderId"))
