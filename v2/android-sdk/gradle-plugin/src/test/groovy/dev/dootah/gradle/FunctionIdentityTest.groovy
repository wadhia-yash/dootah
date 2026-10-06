package dev.dootah.gradle

import org.junit.Test
import static org.junit.Assert.*

class FunctionIdentityTest {
    @Test void identityHasVersionedGoldenAndSeparatesReceiversAndNullability() {
        def of = dev.dootah.identity.FunctionIdentity.&of
        assertEquals('dth1:2c10315110b96541844254b7424bd34945adb697b298ea78ce4e0e5c26e4ca85',
            of('dev/dootah/consumer/ComposeFormsKt', 'CheckoutScreen', '(Landroidx/compose/runtime/Composer;I)V', true, false, null, ''))
        def descriptor = '(Ljava/lang/String;Landroidx/compose/runtime/Composer;I)V'
        def variants = [[true, false, '!'], [false, false, '!'], [true, true, '!'], [true, false, '?']]
        assertEquals(4, variants.collect { of('example/Owner', 'render', descriptor, it[0], it[1], null, it[2]) }.toSet().size())
    }
}
