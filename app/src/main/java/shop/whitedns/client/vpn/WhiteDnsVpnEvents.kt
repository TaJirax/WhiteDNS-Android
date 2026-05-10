package shop.whitedns.client.vpn

import java.util.concurrent.CopyOnWriteArraySet

sealed class WhiteDnsVpnEvent {
    data class Log(val message: String) : WhiteDnsVpnEvent()
    data class Ready(val message: String) : WhiteDnsVpnEvent()
    data class Failed(val message: String) : WhiteDnsVpnEvent()
}

object WhiteDnsVpnEvents {
    private val listeners = CopyOnWriteArraySet<(WhiteDnsVpnEvent) -> Unit>()

    fun addListener(listener: (WhiteDnsVpnEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (WhiteDnsVpnEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun log(message: String) {
        emit(WhiteDnsVpnEvent.Log(message))
    }

    fun ready(message: String) {
        emit(WhiteDnsVpnEvent.Ready(message))
    }

    fun failed(message: String) {
        emit(WhiteDnsVpnEvent.Failed(message))
    }

    private fun emit(event: WhiteDnsVpnEvent) {
        listeners.forEach { listener ->
            runCatching { listener(event) }
        }
    }
}
