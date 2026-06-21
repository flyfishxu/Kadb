package com.flyfishxu.kadb.mdns.internal

internal interface JmDnsBackend : AutoCloseable {
    fun addServiceListener(type: String, listener: JmDnsServiceEvents)

    fun removeServiceListener(type: String, listener: JmDnsServiceEvents)

    fun requestServiceInfo(type: String, name: String)
}

internal interface JmDnsServiceEvents {
    fun serviceAdded(type: String, name: String)

    fun serviceResolved(type: String, name: String, hostAddresses: Array<String>, port: Int)

    fun serviceRemoved(type: String, name: String)
}
