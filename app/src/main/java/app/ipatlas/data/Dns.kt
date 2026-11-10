package app.ipatlas.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder

/** A domain's addresses, and any aliases (CNAMEs) on the way to them. */
data class Resolved(val host: String, val addresses: List<IpAddress>, val aliases: List<String>, val via: String)

/** The domain doesn't exist. */
class NoSuchDomain(host: String) : Exception("$host doesn't exist. Check the spelling.")

/**
 * DNS lookups over HTTPS (Cloudflare first, then Google), so they work the same on any network and can ask
 * for any record type. Falls back to the phone's own resolver for plain addresses.
 */
object Dns {
    private val TYPES = mapOf(
        1 to "A", 2 to "NS", 5 to "CNAME", 6 to "SOA", 12 to "PTR", 15 to "MX", 16 to "TXT",
        28 to "AAAA", 33 to "SRV", 65 to "HTTPS", 257 to "CAA",
    )

    /** Raw answers for one name and record type. Throws [NoSuchDomain] when the name doesn't exist. */
    suspend fun query(name: String, type: String): List<DnsRecord> {
        val q = "?name=" + URLEncoder.encode(name, "UTF-8") + "&type=" + type
        val j = try {
            Http.json("https://cloudflare-dns.com/dns-query$q", accept = "application/dns-json")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Http.json("https://dns.google/resolve$q")
        }
        return parse(name, j)
    }

    fun parse(name: String, j: JSONObject): List<DnsRecord> {
        when (j.optInt("Status", -1)) {
            0 -> Unit
            3 -> throw NoSuchDomain(name)
            2 -> throw LookupError("The DNS server failed to answer for $name.")
            5 -> throw LookupError("The DNS server refused to answer for $name.")
            else -> throw LookupError("DNS lookup for $name didn't work.")
        }
        val answers = j.arr("Answer") ?: return emptyList()
        return answers.objects().map { a ->
            val code = a.optInt("type")
            DnsRecord(
                type = TYPES[code] ?: "TYPE$code",
                name = a.optString("name").trimEnd('.'),
                ttl = a.optLong("TTL"),
                data = clean(TYPES[code], a.optString("data")),
            )
        }
    }

    private fun clean(type: String?, data: String): String = when (type) {
        "CNAME", "NS", "PTR" -> data.trimEnd('.')
        "MX" -> data.trimEnd('.')
        "TXT" -> data.trim().removeSurrounding("\"").replace("\" \"", "")
        else -> data
    }

    /** A and AAAA addresses for a domain. */
    suspend fun resolve(host: String): Resolved {
        val answers: List<DnsRecord>? = try {
            supervisorScope {
                val a = async { query(host, "A") }
                val aaaa = async { query(host, "AAAA") }
                a.await() + aaaa.await()
            }
        } catch (e: NoSuchDomain) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        if (answers != null) {
            val addresses = answers.filter { it.type == "A" || it.type == "AAAA" }
                .mapNotNull { IpAddress.parse(it.data) }
                .distinct()
                .sortedBy { it.version }
            val aliases = answers.filter { it.type == "CNAME" }.map { it.data }.distinct()
            return Resolved(host, addresses, aliases, "Cloudflare DNS")
        }
        // DNS over HTTPS is blocked or down: ask the phone's own resolver instead.
        return withContext(Dispatchers.IO) {
            val found = try {
                InetAddress.getAllByName(host).toList()
            } catch (u: java.net.UnknownHostException) {
                throw NoSuchDomain(host)
            }
            Resolved(
                host,
                found.mapNotNull { IpAddress.of(it.address) }.distinct().sortedBy { it.version },
                emptyList(),
                "this phone's DNS",
            )
        }
    }

    /** The host name an address points back to (its PTR record), or null if it has none. */
    suspend fun reverse(ip: IpAddress): String? {
        return try {
            query(ip.reverseName, "PTR").firstOrNull { it.type == "PTR" }?.data?.takeIf { it.isNotEmpty() }
        } catch (e: NoSuchDomain) {
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            withContext(Dispatchers.IO) {
                val name = InetAddress.getByAddress(ip.bytes()).canonicalHostName
                name.takeIf { IpAddress.parse(it) == null && it.isNotBlank() }
            }
        }
    }

    /** Mail, name server and text records for a domain, by type. Missing types are left out. */
    suspend fun records(host: String): Map<String, List<DnsRecord>> = supervisorScope {
        val types = listOf("MX", "NS", "TXT", "CAA")
        val jobs = types.associateWith { t -> async { query(host, t).filter { it.type == t } } }
        val out = LinkedHashMap<String, List<DnsRecord>>()
        var failure: Throwable? = null
        var failed = 0
        for ((t, job) in jobs) {
            val list = try {
                job.await()
            } catch (e: NoSuchDomain) {
                emptyList()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failure = e
                failed++
                emptyList()
            }
            if (list.isNotEmpty()) {
                out[t] = if (t == "MX") list.sortedBy { it.data.substringBefore(' ').toIntOrNull() ?: 0 } else list
            }
        }
        if (failed == types.size) throw failure ?: LookupError("DNS lookups didn't work.")
        out
    }
}
