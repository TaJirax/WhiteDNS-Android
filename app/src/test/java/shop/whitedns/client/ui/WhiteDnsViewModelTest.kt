package shop.whitedns.client.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.whitedns.client.runtime.WhiteDnsRuntimeStateStore

class WhiteDnsViewModelTest {
    @Test
    fun shouldUseParallelTestForConnectionUsesParallelWhenSelected() {
        assertTrue(
            shouldUseParallelTestForConnection(
                autoTuneEnabled = true,
                fastConnectEnabled = false,
                connectionMode = WhiteDnsRuntimeStateStore.ModeProxy,
            ),
        )
        assertTrue(
            shouldUseParallelTestForConnection(
                autoTuneEnabled = true,
                fastConnectEnabled = false,
                connectionMode = WhiteDnsRuntimeStateStore.ModeVpn,
            ),
        )
    }

    @Test
    fun shouldUseParallelTestForConnectionLetsFastConnectWin() {
        assertFalse(
            shouldUseParallelTestForConnection(
                autoTuneEnabled = true,
                fastConnectEnabled = true,
                connectionMode = WhiteDnsRuntimeStateStore.ModeProxy,
            ),
        )
        assertFalse(
            shouldUseParallelTestForConnection(
                autoTuneEnabled = true,
                fastConnectEnabled = true,
                connectionMode = WhiteDnsRuntimeStateStore.ModeVpn,
            ),
        )
    }

    @Test
    fun shouldUseParallelTestForConnectionRespectsParallelOff() {
        assertFalse(
            shouldUseParallelTestForConnection(
                autoTuneEnabled = false,
                fastConnectEnabled = false,
                connectionMode = WhiteDnsRuntimeStateStore.ModeProxy,
            ),
        )
    }
}
