package app.ipatlas.data

import java.math.BigInteger
import java.net.IDN
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** What kind of address something is, worked out from the address alone (no network needed). */
enum class IpKind(val label: String, val detail: String, val isPublic: Boolean) {
    PUBLIC("Public", "A public internet address.", true),
    PRIVATE("Private", "Used inside home and office networks (RFC 1918). It has no location on the internet.", false),
    SHARED("Carrier NAT", "Shared address space your carrier uses behind its own NAT (RFC 6598).", false),
    LOOPBACK("Loopback", "Points back at the same device.", false),
    LINK_LOCAL("Link-local", "Only works on the local link, like a single Wi-Fi network.", false),
    UNSPECIFIED("Unspecified", "Means \"no address\" or \"this network\".", false),
    DOCUMENTATION("Documentation", "Reserved for examples in documentation. It's never used on the internet.", false),
    BENCHMARK("Benchmarking", "Reserved for network testing (RFC 2544).", false),
    MULTICAST("Multicast", "Sends to a group of listeners instead of one device.", false),
    BROADCAST("Broadcast", "Reaches every device on the local network.", false),
    RESERVED("Reserved", "Set aside by the IETF and not used on the public internet.", false),
    UNIQUE_LOCAL("Unique local", "IPv6's version of a private address (fc00::/7).", false),
    SITE_LOCAL("Site-local", "An old, retired kind of private IPv6 address.", false),
    MAPPED("IPv4-mapped", "An IPv4 address written as IPv6.", true),
    NAT64("NAT64", "An IPv6 stand-in for an IPv4 address, used by NAT64 gateways.", true),
    SIX_TO_FOUR("6to4", "IPv6 carried over IPv4; the IPv4 address is built in.", true),
    TEREDO("Teredo", "IPv6 tunnelled over IPv4 through a Teredo server.", true),
}

/** An IPv4 (4 bytes) or IPv6 (16 bytes) address. */
class IpAddress private constructor(private val b: ByteArray) {

    val version: Int get() = if (b.size == 4) 4 else 6

    fun bytes(): ByteArray = b.copyOf()

    private fun u(i: Int): Int = b[i].toInt() and 0xff

    private fun group(i: Int): Int = (u(2 * i) shl 8) or u(2 * i + 1)

    override fun equals(other: Any?): Boolean = other is IpAddress && other.b.contentEquals(b)

    override fun hashCode(): Int = b.contentHashCode()

    /** The usual way to write it: dotted for IPv4, RFC 5952 compressed for IPv6. */
    override fun toString(): String = if (version == 4) dotted() else compressed()

    private fun dotted(): String = "${u(0)}.${u(1)}.${u(2)}.${u(3)}"

    private fun compressed(): String {
        // IPv4-mapped addresses keep the familiar dotted ending.
        if (isMapped()) return "::ffff:" + IpAddress(b.copyOfRange(12, 16)).dotted()
        val g = IntArray(8) { group(it) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen) {
                    bestLen = j - i
                    bestStart = i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) bestStart = -1
        val sb = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                sb.append("::")
                i += bestLen
                continue
            }
            if (sb.isNotEmpty() && !sb.endsWith(":")) sb.append(':')
            sb.append(Integer.toHexString(g[i]))
            i++
        }
        return sb.toString()
    }

    /** IPv6 written out in full: eight groups of four hex digits. */
    val expanded: String
        get() = if (version == 4) dotted() else (0 until 8).joinToString(":") { String.format(Locale.US, "%04x", group(it)) }

    /** The address as one number. */
    val decimal: String get() = BigInteger(1, b).toString()

    val hex: String get() = "0x" + b.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }

    /** IPv4 as four groups of eight bits. */
    val binary: String get() = b.joinToString(".") { Integer.toBinaryString((it.toInt() and 0xff) or 0x100).substring(1) }

    /** The name a reverse DNS (PTR) lookup asks for. */
    val reverseName: String
        get() = if (version == 4) {
            "${u(3)}.${u(2)}.${u(1)}.${u(0)}.in-addr.arpa"
        } else {
            val nibbles = StringBuilder()
            for (i in 15 downTo 0) {
                nibbles.append(Integer.toHexString(u(i) and 0xf)).append('.')
                nibbles.append(Integer.toHexString(u(i) shr 4)).append('.')
            }
            nibbles.append("ip6.arpa").toString()
        }

    private fun isMapped(): Boolean =
        version == 6 && (0 until 10).all { b[it].toInt() == 0 } && u(10) == 0xff && u(11) == 0xff

    /** An IPv4 address built into this IPv6 one (mapped, NAT64, 6to4), if there is one. */
    val embeddedV4: IpAddress?
        get() = when {
            version == 4 -> null
            isMapped() -> IpAddress(b.copyOfRange(12, 16))
            inV6(NAT64_PREFIX, 96) -> IpAddress(b.copyOfRange(12, 16))
            group(0) == 0x2002 -> IpAddress(b.copyOfRange(2, 6))
            else -> null
        }

    /** For Teredo addresses: the client's real IPv4 address (stored inverted in the last 32 bits). */
    val teredoClient: IpAddress?
        get() = if (version == 6 && group(0) == 0x2001 && group(1) == 0) {
            IpAddress(ByteArray(4) { (b[12 + it].toInt() xor 0xff).toByte() })
        } else {
            null
        }

    /** For IPv6 interface IDs made from a MAC address (EUI-64): that MAC address. */
    val eui64Mac: String?
        get() {
            if (version != 6 || u(11) != 0xff || u(12) != 0xfe) return null
            val mac = intArrayOf(u(8) xor 0x02, u(9), u(10), u(13), u(14), u(15))
            return mac.joinToString(":") { String.format(Locale.US, "%02x", it) }
        }

    /** The /64 network part of an IPv6 address, like 2001:db8:1:2::/64. */
    val prefix64: String?
        get() = if (version == 6) IpAddress(b.copyOfRange(0, 8) + ByteArray(8)).compressed() + "/64" else null

    /** The interface ID: the last 64 bits of an IPv6 address. */
    val interfaceId: String?
        get() = if (version == 6) {
            (4 until 8).joinToString(":") { Integer.toHexString(group(it)) }
        } else {
            null
        }

    /** The old class A/B/C/D/E an IPv4 address falls in (still how many people talk about ranges). */
    val legacyClass: String?
        get() {
            if (version != 4) return null
            val first = u(0)
            return when {
                first < 128 -> "A"
                first < 192 -> "B"
                first < 224 -> "C"
                first < 240 -> "D (multicast)"
                else -> "E (reserved)"
            }
        }

    private fun inV4(prefix: Long, bits: Int): Boolean {
        if (version != 4) return false
        val value = (u(0).toLong() shl 24) or (u(1).toLong() shl 16) or (u(2).toLong() shl 8) or u(3).toLong()
        val mask = if (bits == 0) 0L else (0xffffffffL shl (32 - bits)) and 0xffffffffL
        return (value and mask) == (prefix and mask)
    }

    private fun inV6(prefix: ByteArray, bits: Int): Boolean {
        if (version != 6) return false
        var left = bits
        var i = 0
        while (left > 0) {
            val take = min(8, left)
            val mask = (0xff shl (8 - take)) and 0xff
            if ((u(i) and mask) != (prefix[i].toInt() and 0xff and mask)) return false
            left -= take
            i++
        }
        return true
    }

    val kind: IpKind
        get() = if (version == 4) kind4() else kind6()

    private fun kind4(): IpKind = when {
        inV4(0x00000000L, 8) -> IpKind.UNSPECIFIED
        inV4(0x0A000000L, 8) || inV4(0xAC100000L, 12) || inV4(0xC0A80000L, 16) -> IpKind.PRIVATE
        inV4(0x64400000L, 10) -> IpKind.SHARED
        inV4(0x7F000000L, 8) -> IpKind.LOOPBACK
        inV4(0xA9FE0000L, 16) -> IpKind.LINK_LOCAL
        inV4(0xC0000200L, 24) || inV4(0xC6336400L, 24) || inV4(0xCB007100L, 24) -> IpKind.DOCUMENTATION
        inV4(0xC0000000L, 24) -> IpKind.RESERVED
        inV4(0xC6120000L, 15) -> IpKind.BENCHMARK
        inV4(0xE0000000L, 4) -> IpKind.MULTICAST
        inV4(0xFFFFFFFFL, 32) -> IpKind.BROADCAST
        inV4(0xF0000000L, 4) -> IpKind.RESERVED
        else -> IpKind.PUBLIC
    }

    private fun kind6(): IpKind = when {
        b.all { it.toInt() == 0 } -> IpKind.UNSPECIFIED
        (0 until 15).all { b[it].toInt() == 0 } && u(15) == 1 -> IpKind.LOOPBACK
        isMapped() -> IpKind.MAPPED
        inV6(NAT64_PREFIX, 96) -> IpKind.NAT64
        inV6(byteArrayOf(0x01, 0x00, 0, 0, 0, 0, 0, 0), 64) -> IpKind.RESERVED
        inV6(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()), 32) -> IpKind.DOCUMENTATION
        inV6(byteArrayOf(0x3f, 0xff.toByte(), 0x00), 20) -> IpKind.DOCUMENTATION
        inV6(byteArrayOf(0x20, 0x01, 0x00, 0x00), 32) -> IpKind.TEREDO
        inV6(byteArrayOf(0x20, 0x02), 16) -> IpKind.SIX_TO_FOUR
        inV6(byteArrayOf(0xfc.toByte()), 7) -> IpKind.UNIQUE_LOCAL
        inV6(byteArrayOf(0xfe.toByte(), 0x80.toByte()), 10) -> IpKind.LINK_LOCAL
        inV6(byteArrayOf(0xfe.toByte(), 0xc0.toByte()), 10) -> IpKind.SITE_LOCAL
        inV6(byteArrayOf(0xff.toByte()), 8) -> IpKind.MULTICAST
        inV6(byteArrayOf(0x20), 3) -> IpKind.PUBLIC
        else -> IpKind.RESERVED
    }

    /**
     * The address to send to the location services: the IPv4 address inside a mapped/NAT64/6to4 address,
     * or this one. Null when the address can't have a location (private, loopback and so on).
     */
    val lookupTarget: IpAddress?
        get() {
            embeddedV4?.let { v4 -> return if (v4.kind.isPublic) v4 else null }
            return if (kind == IpKind.PUBLIC || kind == IpKind.TEREDO) this else null
        }

    companion object {
        private val NAT64_PREFIX = byteArrayOf(0x00, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)

        fun of(bytes: ByteArray): IpAddress? = if (bytes.size == 4 || bytes.size == 16) IpAddress(bytes.copyOf()) else null

        /** Reads a literal IPv4 or IPv6 address. Never touches the network. */
        fun parse(text: String): IpAddress? {
            var s = text.trim()
            if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
            val zone = s.indexOf('%')
            if (zone >= 0 && s.contains(':')) s = s.substring(0, zone)
            if (s.isEmpty()) return null
            return if (s.contains(':')) parse6(s) else parse4(s)?.let { IpAddress(it) }
        }

        private fun parse4(s: String): ByteArray? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }

        private fun parse6(s: String): IpAddress? {
            if (s.count { it == ':' } < 2) return null
            val doubles = s.split("::")
            if (doubles.size > 2) return null
            fun groups(part: String): List<String>? = if (part.isEmpty()) emptyList() else part.split(':').takeIf { list -> list.none { it.isEmpty() } }
            val head = groups(doubles[0]) ?: return null
            val tail = if (doubles.size == 2) groups(doubles[1]) ?: return null else emptyList()
            if (doubles.size == 2 && head.any { it.contains('.') }) return null
            val words = ArrayList<Int>()
            val all = head + tail
            for ((index, g) in all.withIndex()) {
                if (g.contains('.')) {
                    // An IPv4 ending is only allowed as the very last piece.
                    if (index != all.lastIndex) return null
                    val v4 = parse4(g) ?: return null
                    words.add(((v4[0].toInt() and 0xff) shl 8) or (v4[1].toInt() and 0xff))
                    words.add(((v4[2].toInt() and 0xff) shl 8) or (v4[3].toInt() and 0xff))
                } else {
                    if (g.length > 4 || !g.all { it.isHexDigitChar() }) return null
                    words.add(g.toInt(16))
                }
            }
            val headWords = if (doubles.size == 2) {
                head.size + head.count { it.contains('.') }
            } else {
                words.size
            }
            val total = words.size
            val full = IntArray(8)
            if (doubles.size == 2) {
                if (total > 7) return null
                for (i in 0 until headWords) full[i] = words[i]
                val tailCount = total - headWords
                for (i in 0 until tailCount) full[8 - tailCount + i] = words[headWords + i]
            } else {
                if (total != 8) return null
                for (i in 0 until 8) full[i] = words[i]
            }
            val out = ByteArray(16)
            for (i in 0 until 8) {
                out[2 * i] = (full[i] shr 8).toByte()
                out[2 * i + 1] = (full[i] and 0xff).toByte()
            }
            return IpAddress(out)
        }

        private fun Char.isHexDigitChar(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}

/** What someone typed or shared, sorted out. */
sealed interface Query {
    data class Address(val ip: IpAddress) : Query
    data class Host(val name: String) : Query
    data class Invalid(val message: String) : Query
}

object Queries {

    /** Turns typed text (an IP, a domain, a full link, host:port and so on) into something to look up. */
    fun parse(input: String): Query {
        var s = input.trim().trim('"', '\'', '<', '>', '(', ')', ',', ';')
        if (s.isEmpty()) return Query.Invalid("Type an IP address or a domain.")
        IpAddress.parse(s)?.let { return Query.Address(it) }

        // A link: keep just the host part.
        val scheme = s.indexOf("://")
        if (scheme in 1..12) s = s.substring(scheme + 3)
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in s) s = s.substringAfterLast('@')
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end > 0) s = s.substring(1, end)
        } else if (s.count { it == ':' } == 1) {
            // host:port
            val port = s.substringAfter(':')
            if (port.all { it.isDigit() }) s = s.substringBefore(':')
        }
        IpAddress.parse(s)?.let { return Query.Address(it) }

        s = s.trimEnd('.').lowercase(Locale.ROOT)
        if (s.isEmpty()) return Query.Invalid("Type an IP address or a domain.")
        if (s.all { it.isDigit() || it == '.' }) {
            return Query.Invalid("$s isn't a complete IPv4 address. It needs four numbers from 0 to 255, like 8.8.8.8.")
        }
        if (s.contains(':')) return Query.Invalid("$s isn't a valid IPv6 address.")
        val ascii = try {
            IDN.toASCII(s, IDN.ALLOW_UNASSIGNED)
        } catch (e: IllegalArgumentException) {
            return Query.Invalid("$s isn't a valid domain name.")
        }
        val labels = ascii.split('.')
        val okLabels = labels.all { label ->
            label.isNotEmpty() && label.length <= 63 && !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        }
        if (!okLabels || ascii.length > 253) return Query.Invalid("$s isn't a valid domain name.")
        if (labels.size < 2) return Query.Invalid("Add the ending to $s, like $s.com.")
        if (labels.last().all { it.isDigit() }) return Query.Invalid("$s isn't a valid domain name.")
        return Query.Host(ascii)
    }

    /** The first IP address, link or domain in a piece of shared or selected text. */
    fun findIn(text: String): String? {
        val tokens = text.split(Regex("[\\s,;<>\"'()|]+")).map { it.trim().trimEnd('.', ':', '!', '?') }.filter { it.isNotEmpty() }
        tokens.firstOrNull { parse(it) is Query.Address }?.let { return it }
        return tokens.firstOrNull { token ->
            parse(token) is Query.Host && (token.contains("://") || token.contains('.'))
        }
    }
}

object Geo {
    /** Great-circle distance in kilometers. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** 37°24′20″N 122°04′39″W */
    fun dms(lat: Double, lon: Double): String = part(lat, "N", "S") + "  " + part(lon, "E", "W")

    private fun part(value: Double, pos: String, neg: String): String {
        var total = Math.round(abs(value) * 3600.0)
        val deg = total / 3600
        total %= 3600
        val min = total / 60
        val sec = total % 60
        return String.format(Locale.US, "%d°%02d′%02d″%s", deg, min, sec, if (value >= 0) pos else neg)
    }

    /** 37.40560, −122.07750 */
    fun decimal(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lon).replace("-", "−")

    /** 🇺🇸 from "US". */
    fun flag(countryCode: String?): String {
        val cc = countryCode?.trim()?.uppercase(Locale.ROOT) ?: return ""
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return ""
        val first = Character.toChars(0x1F1E6 + (cc[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (cc[1] - 'A'))
        return String(first) + String(second)
    }
}
