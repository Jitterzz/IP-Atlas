package app.ipatlas.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Registration records from the internet registries (ARIN, RIPE NCC, APNIC, LACNIC, AFRINIC) over RDAP.
 * rdap.org sends each request on to whichever registry is in charge of the address.
 */
object Rdap {

    suspend fun lookup(ip: IpAddress): RdapInfo =
        parse(Http.json("https://rdap.org/ip/$ip", accept = "application/rdap+json, application/json"))

    fun parse(j: JSONObject): RdapInfo {
        if (j.str("objectClassName") != "ip network" && j.str("startAddress") == null) {
            val title = j.str("title") ?: j.arr("description")?.strings()?.firstOrNull()
            throw LookupError(title ?: "The registry has no record for this address.")
        }
        val start = j.str("startAddress")
        val end = j.str("endAddress")
        val cidrs = j.arr("cidr0_cidrs")?.objects()?.mapNotNull { c ->
            val prefix = c.str("v4prefix") ?: c.str("v6prefix") ?: return@mapNotNull null
            val len = c.num("length")?.toInt() ?: return@mapNotNull null
            "$prefix/$len"
        }.orEmpty()
        var registered: String? = null
        var updated: String? = null
        j.arr("events")?.objects()?.forEach { e ->
            when (e.str("eventAction")?.lowercase()) {
                "registration" -> registered = date(e.str("eventDate"))
                "last changed" -> updated = date(e.str("eventDate"))
            }
        }
        val entities = j.arr("entities")
        val org = findEntity(entities, "registrant")?.let { vcardName(it) }
            ?: findEntity(entities, "administrative")?.let { vcardName(it) }
        val abuse = findEntity(entities, "abuse")?.let { vcardEmail(it) }
        return RdapInfo(
            name = j.str("name"),
            handle = j.str("handle"),
            range = if (start != null && end != null) "$start – $end" else null,
            cidr = cidrs.joinToString(", ").ifEmpty { null },
            type = j.str("type")?.let { pretty(it) },
            country = j.str("country"),
            registered = registered,
            updated = updated,
            status = j.arr("status")?.strings()?.joinToString(", ") { pretty(it) }?.ifEmpty { null },
            registry = registryOf(j.str("port43")) ?: registryOf(j.arr("links")?.objects()?.firstOrNull()?.str("value")),
            org = org,
            abuseEmail = abuse,
            parent = j.str("parentHandle"),
        )
    }

    private fun pretty(s: String): String = s.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun date(s: String?): String? {
        if (s == null) return null
        return try {
            OffsetDateTime.parse(s).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
        } catch (e: Exception) {
            s.take(10)
        }
    }

    private fun registryOf(hint: String?): String? {
        val h = hint?.lowercase() ?: return null
        return when {
            "arin" in h -> "ARIN (North America)"
            "ripe" in h -> "RIPE NCC (Europe, Middle East)"
            "apnic" in h -> "APNIC (Asia Pacific)"
            "lacnic" in h -> "LACNIC (Latin America)"
            "afrinic" in h -> "AFRINIC (Africa)"
            else -> null
        }
    }

    /** First entity with [role], searching nested entities too (abuse contacts often sit inside the org). */
    private fun findEntity(list: JSONArray?, role: String): JSONObject? {
        if (list == null) return null
        for (e in list.objects()) {
            val roles = e.arr("roles")?.strings()?.map { it.lowercase() }.orEmpty()
            if (role in roles) return e
        }
        for (e in list.objects()) {
            findEntity(e.arr("entities"), role)?.let { return it }
        }
        return null
    }

    /** vcardArray = ["vcard", [["fn", {}, "text", "Google LLC"], ["email", {}, "text", "a@b.c"], ...]] */
    private fun vcardField(entity: JSONObject, field: String): String? {
        val card = entity.arr("vcardArray") ?: return null
        val props = card.optJSONArray(1) ?: return null
        for (i in 0 until props.length()) {
            val p = props.optJSONArray(i) ?: continue
            if (p.optString(0).equals(field, ignoreCase = true)) {
                val value = p.opt(3)
                if (value is String && value.isNotBlank()) return value.trim()
            }
        }
        return null
    }

    private fun vcardName(entity: JSONObject): String? = vcardField(entity, "fn") ?: entity.str("handle")

    private fun vcardEmail(entity: JSONObject): String? = vcardField(entity, "email")
}
