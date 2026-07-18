package shop.whitedns.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import shop.whitedns.client.model.WhiteDnsSettings

class RuntimeLaunchRequestStoreTest {
    @Test
    fun `runtime settings preserve CottenDns preset overrides`() {
        val original = WhiteDnsSettings(
            configPreset = "survival",
            transportMode = "tcp",
            deliveryMode = "txt-https",
            qnameMode = "aggressive",
        )

        val restored = RuntimeLaunchRequestStore.decodeSettings(
            RuntimeLaunchRequestStore.encodeSettings(original),
        )

        assertEquals(original.configPreset, restored.configPreset)
        assertEquals(original.transportMode, restored.transportMode)
        assertEquals(original.deliveryMode, restored.deliveryMode)
        assertEquals(original.qnameMode, restored.qnameMode)
    }
}
