package com.flyfishxu.kadb.mdns.internal

internal class FakeJmDnsBackend : JmDnsBackend {
    private val listeners = linkedMapOf<String, JmDnsServiceEvents>()
    val removedListenerTypes = linkedSetOf<String>()
    var closed = false
        private set

    override fun addServiceListener(type: String, listener: JmDnsServiceEvents) {
        listeners[type] = listener
    }

    override fun removeServiceListener(type: String, listener: JmDnsServiceEvents) {
        if (listeners[type] == listener) {
            listeners.remove(type)
        }
        removedListenerTypes += type
    }

    override fun requestServiceInfo(type: String, name: String) = Unit

    override fun close() {
        closed = true
    }

    fun emitAdded(type: String, name: String, host: String, port: Int) {
        val listener = listeners[type.removeSuffix(".local.").removeSuffix(".local")] ?: listeners[type]
        listener?.serviceAdded(type, name)
        listener?.serviceResolved(type, name, arrayOf(host), port)
    }

    fun emitRemoved(type: String, name: String) {
        val listener = listeners[type.removeSuffix(".local.").removeSuffix(".local")] ?: listeners[type]
        listener?.serviceRemoved(type, name)
    }
}
