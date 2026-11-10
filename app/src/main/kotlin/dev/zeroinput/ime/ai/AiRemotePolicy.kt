package dev.zeroinput.ime.ai

import java.net.InetAddress
import java.net.URI

/** Public names only; actual resolved addresses must also pass at socket creation. */
internal object AiRemotePolicy {
    fun endpoint(value: String): URI {
        require(value.length in 1..2_048 && value.none(Char::isISOControl))
        val uri = URI(value)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1, 443))
        val host = requireNotNull(uri.host).lowercase()
        require(host.length <= 253 && host.contains('.') && !host.endsWith('.') && host.none { it == ':' || it == '[' || it == ']' })
        require(host.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) })
        require(host.last().isLetter() && listOf(".localhost", ".local", ".internal", ".home.arpa", ".test", ".invalid", ".example", ".onion").none(host::endsWith))
        return uri
    }
    fun publicAddress(address: InetAddress): Boolean = publicBytes(address.address)
    fun publicAnswers(addresses: List<InetAddress>) = addresses.isNotEmpty() && addresses.size <= 16 && addresses.all(::publicAddress)
    fun publicBytes(bytes: ByteArray): Boolean {
        val b = bytes.map { it.toInt() and 255 }
        if (b.size == 4) {
            if (b[0] in setOf(0, 10, 127) || b[0] >= 224) return false
            if (b[0] == 100 && b[1] in 64..127 || b[0] == 169 && b[1] == 254 || b[0] == 172 && b[1] in 16..31) return false
            if (b[0] == 192 && (b[1] == 168 || b[1] == 0 || b[1] == 88 && b[2] == 99)) return false
            if (b[0] == 198 && (b[1] in 18..19 || b[1] == 51 && b[2] == 100)) return false
            if (b[0] == 203 && b[1] == 0 && b[2] == 113) return false
            return true
        }
        // Only globally routed 2000::/3; exclude special-use, Teredo, 6to4 and documentation ranges.
        if (b.size != 16 || b[0] !in 0x20..0x3f) return false
        if (b[0] == 0x20 && b[1] == 0x01 && (b[2] < 2 || b[2] == 0x0d && b[3] == 0xb8)) return false
        if (b[0] == 0x20 && b[1] == 0x02 || b[0] == 0x3f && b[1] == 0xff && b[2] < 16) return false
        return true
    }
    fun source(value: String): Boolean = runCatching {
        val uri = URI(value)
        value.length <= 2_048 && value.none(Char::isISOControl) && uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}
