package io.github.matthewjones372.proofload.java

import io.github.matthewjones372.proofload.Offered
import java.time.Duration as JavaDuration

/**
 * What a run offered, read where its value classes are still nameable.
 *
 * `Offered` itself crosses a signature intact; both of its rates and its window
 * do not, which is why [Offereds] is the Java source over this.
 */
internal object Offers {

    @JvmStatic
    @JvmName("asked")
    fun asked(offered: Offered): Any = offered.asked

    @JvmStatic
    @JvmName("left")
    fun left(offered: Offered): Any = offered.left

    @JvmStatic
    @JvmName("over")
    fun over(offered: Offered): JavaDuration = offered.over.asJava()
}
