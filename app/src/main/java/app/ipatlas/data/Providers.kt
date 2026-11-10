package app.ipatlas.data

import org.json.JSONObject
import java.net.URLEncoder

/** A lookup that worked, plus notes about any services that didn't answer first. */
data class Located(val info: IpInfo, val notes: List<String>)

object Providers {

    /** Asks each service in [order] until one answers. [ip] null means "my own address". */
    suspend fun locate(ip: String?, order: List<Source>): Located {
        val notes = ArrayList<String>()
        var last: Throwable? = null
        for (source in order) {
            try {
                val info = lookup(source, ip)
                return Located(info, notes)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                notes.add("${source.label} ${Http.reason(e)}")
            }
        }
        throw LookupError(
            if (notes.isEmpty()) "No location service is turned on." else notes.joinToString(". ") + ".",
        ).also { if (last != null) it.initCause(last) }
    }

    suspend fun lookup(source: Source, ip: String?): IpInfo = when (source) {
        Source.IPAPI_IS -> ipapiIs(ip)
        Source.IPWHO_IS -> ipwhoIs(ip)
        Source.FREEIPAPI -> freeIpApi(ip)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ipapi.is: https://api.ipapi.is/?q=8.8.8.8 (no q = the caller's address)
    private suspend fun ipapiIs(ip: String?): IpInfo {
        val url = if (ip == null) "https://api.ipapi.is/" else "https://api.ipapi.is/?q=" + enc(ip)
        return parseIpapiIs(Http.json(url))
    }

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
        val crawler = if (j.has("is_crawler") && j.opt("is_crawler") is String) j.str("is_crawler") else null
        return IpInfo(
            ip = ip,
            source = Source.IPAPI_IS,
            continent = continentName(loc?.str("continent")),
            country = loc?.str("country"),
            countryCode = loc?.str("country_code"),
            region = loc?.str("state"),
            city = loc?.str("city"),
            postal = loc?.str("zip"),
            latitude = loc?.num("latitude"),
            longitude = loc?.num("longitude"),
            accuracy = loc?.str("accuracy")?.let { accuracyWords(it) },
            timeZone = loc?.str("timezone"),
            callingCode = loc?.str("calling_code")?.let { plus(it) },
            currency = loc?.str("currency_code"),
            isEu = loc?.bool("is_eu_member"),
            isp = company?.str("name"),
            companyType = company?.str("type"),
            domain = company?.str("domain"),
            network = company?.str("network"),
            netName = company?.str("netname"),
            abuseScore = company?.str("abuser_score"),
            asn = asn?.num("asn")?.toLong(),
            org = asn?.str("org"),
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
        return IpInfo(
            ip = ip,
            source = Source.IPWHO_IS,
            continent = j.str("continent"),
            country = j.str("country"),
            countryCode = j.str("country_code"),
            region = j.str("region"),
            city = j.str("city"),
            postal = j.str("postal"),
            latitude = j.num("latitude"),
            longitude = j.num("longitude"),
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
