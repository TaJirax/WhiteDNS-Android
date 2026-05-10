package shop.whitedns.client.proxy

import java.util.concurrent.CopyOnWriteArraySet

sealed class WhiteDnsProxyEvent {
    data class Log(val message: String) : WhiteDnsProxyEvent()
    data class Ready(val message: String) : WhiteDnsProxyEvent()
    data class Failed(val message: String) : WhiteDnsProxyEvent()
}

object WhiteDnsProxyEvents {
    private val listeners = CopyOnWriteArraySet<(WhiteDnsProxyEvent) -> Unit>()

    fun addListener(listener: (WhiteDnsProxyEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (WhiteDnsProxyEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun log(message: String) {
        emit(WhiteDnsProxyEvent.Log(message))
    }

    fun ready(message: String) {
        emit(WhiteDnsProxyEvent.Ready(message))
    }

    fun failed(message: String) {
        emit(WhiteDnsProxyEvent.Failed(message))
    }

    private fun emit(event: WhiteDnsProxyEvent) {
        listeners.forEach { listener ->
            runCatching { listener(event) }
        }
    }
}
