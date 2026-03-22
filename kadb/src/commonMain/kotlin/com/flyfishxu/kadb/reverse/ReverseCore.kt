package com.flyfishxu.kadb.reverse

import com.flyfishxu.kadb.Kadb

data class AdbReverseRule(
    val device: String,
    val host: String,
)

internal abstract class BaseReverse {
    protected fun createRule(device: String, host: String, noRebind: Boolean = false) {
        execute(buildReverseForwardDestination(device, host, noRebind))
    }

    protected fun removeRule(device: String) {
        execute(buildReverseKillDestination(device))
    }

    protected fun removeAllRules() {
        execute(buildReverseKillAllDestination())
    }

    protected fun listRules(): List<AdbReverseRule> {
        return parseReverseListOutput(execute(buildReverseListDestination()))
    }

    protected abstract fun execute(destination: String): String
}

internal class AdbReverse(
    private val kadb: Kadb,
) : BaseReverse() {
    fun create(device: String, host: String, noRebind: Boolean = false) {
        createRule(device, host, noRebind)
    }

    fun remove(device: String) {
        removeRule(device)
    }

    fun removeAll() {
        removeAllRules()
    }

    fun list(): List<AdbReverseRule> {
        return listRules()
    }

    override fun execute(destination: String): String {
        return kadb.open(destination).use { stream ->
            val output = stream.source.readUtf8()
            val response = parseSmartSocketResponse(output)
            check(response.status != SmartSocketStatus.FAIL) { "Reverse command failed: ${response.payload}" }
            response.payload
        }
    }
}

internal fun buildReverseForwardDestination(device: String, host: String, noRebind: Boolean = false): String {
    require(device.isNotBlank()) { "device must not be blank" }
    require(host.isNotBlank()) { "host must not be blank" }
    val prefix = if (noRebind) "reverse:forward:norebind:" else "reverse:forward:"
    return "$prefix$device;$host"
}

internal fun buildReverseKillDestination(device: String): String {
    require(device.isNotBlank()) { "device must not be blank" }
    return "reverse:killforward:$device"
}

internal fun buildReverseKillAllDestination(): String = "reverse:killforward-all"

internal fun buildReverseListDestination(): String = "reverse:list-forward"

internal fun parseReverseListOutput(output: String): List<AdbReverseRule> {
    return output
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            when {
                fields.size >= 3 -> AdbReverseRule(device = fields[fields.lastIndex - 1], host = fields.last())
                fields.size == 2 -> AdbReverseRule(device = fields[0], host = fields[1])
                else -> null
            }
        }
        .toList()
}

internal enum class SmartSocketStatus {
    OKAY,
    FAIL,
    UNKNOWN,
}

internal data class SmartSocketResponse(
    val status: SmartSocketStatus,
    val payload: String,
)

internal fun parseSmartSocketResponse(raw: String): SmartSocketResponse {
    return when {
        raw.startsWith("OKAY") -> SmartSocketResponse(SmartSocketStatus.OKAY, decodeProtocolStringOrRaw(raw.removePrefix("OKAY")))
        raw.startsWith("FAIL") -> SmartSocketResponse(SmartSocketStatus.FAIL, decodeProtocolStringOrRaw(raw.removePrefix("FAIL")))
        else -> SmartSocketResponse(SmartSocketStatus.UNKNOWN, decodeProtocolStringOrRaw(raw))
    }
}

private fun decodeProtocolStringOrRaw(content: String): String {
    if (content.length < 4) return content
    val header = content.substring(0, 4)
    val length = header.toIntOrNull(16) ?: return content
    val payloadEnd = 4 + length
    if (content.length < payloadEnd) return content
    return content.substring(4, payloadEnd)
}
