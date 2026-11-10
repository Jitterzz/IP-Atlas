package app.ipatlas.data

import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A lookup that worked, plus notes about any services that didn't answer first. */
data class Located(val info: IpInfo, val notes: List<String>)

/**
 * Services that are out of free lookups, and until when. They're skipped until then, so the app never keeps
 * knocking (ipapi.is blocks a phone for 24 hours if it keeps asking after its daily limit).
 */
object Cooldowns {
    private val until = ConcurrentHashMap<Source, Long>()

    /** Saves the list whenever it changes (set up by [Store]). */
    var persist: ((Map<String, Long>) -> Unit)? = null

    fun load(saved: Map<String, Long>) {
        for ((k, v) in saved) Source.fromKey(k)?.let { until[it] = v }
    }

    fun blocked(source: Source, now: Long = System.currentTimeMillis()): Boolean = (until[source] ?: 0L) > now

    /** "8:00 PM" when it's later today, "tomorrow" otherwise. */
    fun untilText(source: Source): String {
        val at = until[source] ?: return "later"
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return if (day == LocalDate.now(zone)) {
            Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        } else {
            "tomorrow"
        }
    }

    /** Works out how long to leave [source] alone after it failed with [e]. */
    fun note(source: Source, e: Throwable) {
        val now = System.currentTimeMillis()
        val pause: Long? = when {
            e is HttpError && e.code == 429 -> e.retryAfterSec?.let { it * 1000 } ?: when (source) {
                Source.IPAPI_IS -> untilUtcMidnight(now)
                Source.IPWHO_IS -> 24 * 3_600_000L
                Source.FREEIPAPI -> 60_000L
            }
            // ipapi.is answers a blocked phone with a bare 403.
            e is HttpError && e.code == 403 -> 3_600_000L
            e is SocketTimeoutException -> 5 * 60_000L
            else -> null
        }
        if (pause != null) {
            until[source] = now + pause
            persist?.invoke(until.mapKeys { it.key.key })
        }
    }

    fun clear(source: Source) {
        if (until.remove(source) != null) persist?.invoke(until.mapKeys { it.key.key })
    }

    private fun untilUtcMidnight(now: Long): Long {
        val tomorrow = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
        return tomorrow.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - now + 60_000L
    }
}

object Providers {

    /**
     * Asks each service in [order] until one answers. If that answer is missing the city, state, country or
     * spot, the next service fills the gaps. [ip] null means "my own address".
     */
    suspend fun locate(ip: String?, order: List<Source>, ipapiKey: String? = null): Located {
        val notes = ArrayList<String>()
        var found: IpInfo? = null
        for (source in order) {
            if (Cooldowns.blocked(source)) {
                if (found == null) notes.add("${source.label} is out of free lookups until ${Cooldowns.untilText(source)}")
                continue
            }
            try {
                val info = lookup(source, ip ?: found?.ip, ipapiKey)
                Cooldowns.clear(source)
                val first = found
                if (first == null) {
                    found = info
                    if (!info.missingPlace) break
                } else {
                    found = first.fillFrom(info)
                    break
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Cooldowns.note(source, e)
                if (found == null) notes.add("${source.label} ${Http.reason(e)}")
            }
        }
        val info = found ?: throw LookupError(
            if (notes.isEmpty()) "No location service answered." else notes.joinToString(". ") + ".",
        )
        return Located(info, notes)
    }

    suspend fun lookup(source: Source, ip: String?, ipapiKey: String? = null): IpInfo = when (source) {
        Source.IPAPI_IS -> ipapiIs(ip, ipapiKey?.trim()?.takeIf { it.isNotEmpty() })
        Source.IPWHO_IS -> ipwhoIs(ip)
        Source.FREEIPAPI -> freeIpApi(ip)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ipapi.is: https://api.ipapi.is/?q=8.8.8.8&key=... (no q = the caller's address)
    private suspend fun ipapiIs(ip: String?, key: String?): IpInfo {
        val params = listOfNotNull(ip?.let { "q=" + enc(it) }, key?.let { "key=" + enc(it) })
        val url = "https://api.ipapi.is/" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return parseIpapiIs(Http.json(url))
    }

    /**
     * Reads both ipapi.is answers: the full one (with a key: nested location, company and asn objects, and the
     * privacy flags) and the short keyless one (flat city, region, lat, lon, with company and asn as text).
     */
    fun parseIpapiIs(j: JSONObject): IpInfo {
        j.str("error")?.let { throw LookupError(it) }
        j.str("message")?.takeIf { j.str("ip") == null }?.let { throw LookupError(it) }
        val ip = j.str("ip") ?: throw LookupError("ipapi.is sent back an answer with no address in it.")
        val loc = j.obj("location")
        val company = j.obj("company")
        val asn = j.obj("asn")
        val abuse = j.obj("abuse")
        val dc = j.obj("datacenter")
        val vpn = j.obj("vpn")
        // Keyless answers: "company": "Proxad / Free SAS", "asn": "AS12322 Free SAS".
        val companyText = if (company == null) j.str("company") else null
        val asnText = if (asn == null) j.str("asn") else null
        val asnNumber = asnText?.let { Regex("^AS(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val asnTextName = asnText?.replace(Regex("^AS\\d+\\s*", RegexOption.IGNORE_CASE), "")?.trim()?.ifEmpty { null }
        val crawler = if (j.has("is_crawler") && j.opt("is_crawler") is String) j.str("is_crawler") else null
        return IpInfo(
            ip = ip,
            source = Source.IPAPI_IS,
            continent = continentName(loc?.str("continent")),
            country = loc?.str("country") ?: j.str("country"),
            countryCode = loc?.str("country_code") ?: j.str("country_code"),
            region = loc?.str("state") ?: j.str("region") ?: j.str("state"),
            city = loc?.str("city") ?: j.str("city"),
            postal = loc?.str("zip") ?: j.str("zip"),
            latitude = loc?.num("latitude") ?: j.num("lat") ?: j.num("latitude"),
            longitude = loc?.num("longitude") ?: j.num("lon") ?: j.num("longitude"),
            accuracy = loc?.str("accuracy")?.let { accuracyWords(it) },
            timeZone = loc?.str("timezone") ?: j.str("timezone"),
            callingCode = loc?.str("calling_code")?.let { plus(it) },
            currency = loc?.str("currency_code"),
            isEu = loc?.bool("is_eu_member"),
            isp = company?.str("name") ?: companyText,
            companyType = company?.str("type"),
            domain = company?.str("domain"),
            network = company?.str("network"),
            netName = company?.str("netname"),
            abuseScore = company?.str("abuser_score"),
            asn = asn?.num("asn")?.toLong() ?: asnNumber,
            org = asn?.str("org") ?: asnTextName,
            asnName = asn?.str("descr"),
            asnDomain = asn?.str("domain"),
            route = asn?.str("route"),
            asnAbuseScore = asn?.str("abuser_score"),
            rir = j.str("rir") ?: asn?.str("rir"),
            datacenter = dc?.str("datacenter"),
            isVpn = j.bool("is_vpn"),
            isProxy = j.bool("is_proxy"),
            isTor = j.bool("is_tor"),
            isHosting = j.bool("is_datacenter"),
            isAbuser = j.bool("is_abuser"),
            isMobile = j.bool("is_mobile"),
            isCrawler = if (crawler != null) true else j.bool("is_crawler"),
            crawlerName = crawler,
            isSatellite = j.bool("is_satellite"),
            vpnService = vpn?.str("service"),
            abuseName = abuse?.str("name"),
            abuseEmail = abuse?.str("email") ?: asn?.str("abuse"),
            abusePhone = abuse?.str("phone"),
            abuseAddress = abuse?.str("address"),
        )
    }

    // ipwho.is: https://ipwho.is/8.8.8.8 (no address = the caller's)
    private suspend fun ipwhoIs(ip: String?): IpInfo = parseIpwhoIs(Http.json("https://ipwho.is/" + (ip ?: "")))

    fun parseIpwhoIs(j: JSONObject): IpInfo {
        if (j.bool("success") == false) throw LookupError(j.str("message") ?: "ipwho.is couldn't look that up.")
        val ip = j.str("ip") ?: throw LookupError("ipwho.is sent back an answer with no address in it.")
        val conn = j.obj("connection")
        val tz = j.obj("timezone")
        var lat = j.num("latitude")
        var lon = j.num("longitude")
        if (lat == 0.0 && lon == 0.0) {
            lat = null
            lon = null
        }
        return IpInfo(
            ip = ip,
            source = Source.IPWHO_IS,
            continent = j.str("continent"),
            country = j.str("country"),
            countryCode = j.str("country_code"),
            region = j.str("region"),
            city = j.str("city"),
            postal = j.str("postal"),
            latitude = lat,
            longitude = lon,
            timeZone = tz?.str("id"),
            callingCode = j.str("calling_code")?.let { plus(it) },
            capital = j.str("capital"),
            isEu = j.bool("is_eu"),
            isp = conn?.str("isp"),
            org = conn?.str("org"),
            asn = conn?.num("asn")?.toLong(),
            domain = conn?.str("domain"),
        )
    }

    // FreeIPAPI: https://free.freeipapi.com/api/v1/json/8.8.8.8 (no address = the caller's)
    private suspend fun freeIpApi(ip: String?): IpInfo {
        val tail = ip ?: ""
        val j = try {
            Http.json("https://free.freeipapi.com/api/v1/json/$tail")
        } catch (e: HttpError) {
            if (e.code == 404) Http.json("https://free.freeipapi.com/api/json/$tail") else throw e
        }
        return parseFreeIpApi(j)
    }

    fun parseFreeIpApi(j: JSONObject): IpInfo {
        val ip = j.str("ipAddress") ?: throw LookupError(j.str("message") ?: "FreeIPAPI sent back an answer with no address in it.")
        var lat = j.num("latitude")
        var lon = j.num("longitude")
        if (lat == 0.0 && lon == 0.0) {
            lat = null
            lon = null
        }
        val org = j.str("asnOrganization")
        return IpInfo(
            ip = ip,
            source = Source.FREEIPAPI,
            continent = j.str("continent"),
            country = j.str("countryName"),
            countryCode = j.str("countryCode"),
            region = j.str("regionName"),
            city = j.str("cityName"),
            postal = j.str("zipCode"),
            latitude = lat,
            longitude = lon,
            timeZone = j.arr("timeZones")?.strings()?.firstOrNull() ?: j.str("timeZone"),
            callingCode = j.arr("phoneCodes")?.strings()?.firstOrNull()?.let { plus(it) },
            capital = j.str("capital"),
            currency = j.arr("currencies")?.strings()?.joinToString(", ")?.ifEmpty { null },
            languages = j.arr("languages")?.strings()?.joinToString(", ")?.ifEmpty { null },
            isp = org,
            org = org,
            asn = j.str("asn")?.removePrefix("AS")?.toLongOrNull(),
            isProxy = j.bool("isProxy"),
        )
    }

    private fun plus(code: String): String = if (code.startsWith("+")) code else "+$code"

    private fun continentName(code: String?): String? = when (code?.uppercase()) {
        null -> null
        "AF" -> "Africa"
        "AN" -> "Antarctica"
        "AS" -> "Asia"
        "EU" -> "Europe"
        "NA" -> "North America"
        "OC" -> "Oceania"
        "SA" -> "South America"
        else -> code
    }

    private fun accuracyWords(raw: String): String = raw.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
