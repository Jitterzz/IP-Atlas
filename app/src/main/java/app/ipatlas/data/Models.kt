package app.ipatlas.data

/** Everything a location service told us about one address. Anything a service doesn't know stays null. */
data class IpInfo(
    val ip: String,
    val source: Source,
    // Where
    val continent: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val region: String? = null,
    val city: String? = null,
    val postal: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: String? = null,
    val timeZone: String? = null,
    val callingCode: String? = null,
    val currency: String? = null,
    val capital: String? = null,
    val languages: String? = null,
    val isEu: Boolean? = null,
    // Who runs it
    val isp: String? = null,
    val org: String? = null,
    val asn: Long? = null,
    val asnName: String? = null,
    val asnDomain: String? = null,
    val route: String? = null,
    val network: String? = null,
    val netName: String? = null,
    val domain: String? = null,
    val companyType: String? = null,
    val rir: String? = null,
    val datacenter: String? = null,
    // Privacy checks
    val isVpn: Boolean? = null,
    val isProxy: Boolean? = null,
    val isTor: Boolean? = null,
    val isHosting: Boolean? = null,
    val isAbuser: Boolean? = null,
    val isMobile: Boolean? = null,
    val isCrawler: Boolean? = null,
    val isSatellite: Boolean? = null,
    val vpnService: String? = null,
    val crawlerName: String? = null,
    val abuseScore: String? = null,
    val asnAbuseScore: String? = null,
    // Who to tell about abuse
    val abuseName: String? = null,
    val abuseEmail: String? = null,
    val abusePhone: String? = null,
    val abuseAddress: String? = null,
    /** The service that filled in what [source] left out (city, state, coordinates...), if any. */
    val filledFrom: Source? = null,
    /** City and/or state were named from the app's own map data, because no service gave them. */
    val placeFromMap: Boolean = false,
) {
    /** Missing something people look for first: city, state or region, country code, or the spot itself. */
    val missingPlace: Boolean
        get() = city == null || region == null || countryCode == null || latitude == null || longitude == null

    /** This answer with its gaps filled from [other] (another service's answer for the same address). */
    fun fillFrom(other: IpInfo): IpInfo {
        val point = latitude != null && longitude != null
        return copy(
            continent = continent ?: other.continent,
            country = country ?: other.country,
            countryCode = countryCode ?: other.countryCode,
            region = region ?: other.region,
            city = city ?: other.city,
            postal = postal ?: other.postal,
            latitude = if (point) latitude else other.latitude,
            longitude = if (point) longitude else other.longitude,
            timeZone = timeZone ?: other.timeZone,
            callingCode = callingCode ?: other.callingCode,
            currency = currency ?: other.currency,
            capital = capital ?: other.capital,
            languages = languages ?: other.languages,
            isEu = isEu ?: other.isEu,
            isp = isp ?: other.isp,
            org = org ?: other.org,
            asn = asn ?: other.asn,
            asnName = asnName ?: other.asnName,
            domain = domain ?: other.domain,
            isProxy = isProxy ?: other.isProxy,
            filledFrom = other.source,
        )
    }

    val hasPoint: Boolean get() = latitude != null && longitude != null

    /** "Mountain View, California, United States" */
    val place: String
        get() = listOfNotNull(city, region, country).distinct().joinToString(", ")

    /** "Mountain View, California" or the country when that's all there is. */
    val shortPlace: String
        get() = listOfNotNull(city, region).distinct().joinToString(", ").ifEmpty { country ?: "" }

    /** The name people know the network by. */
    val provider: String? get() = isp ?: org ?: asnName

    val asLabel: String? get() = asn?.let { "AS$it" }

    /** A word for what kind of network this is, from the service's own type field. */
    val networkType: String?
        get() = when (companyType?.lowercase()) {
            "isp" -> "Internet provider"
            "hosting" -> "Hosting"
            "business" -> "Business"
            "education" -> "Education"
            "government" -> "Government"
            "banking" -> "Banking"
            null, "" -> if (isHosting == true) "Hosting" else null
            else -> companyType.replaceFirstChar { it.uppercase() }
        }

    val flagged: List<String>
        get() = buildList {
            if (isVpn == true) add("VPN")
            if (isProxy == true) add("Proxy")
            if (isTor == true) add("Tor")
            if (isAbuser == true) add("Known abuse")
        }
}

/** Where location data comes from. All free; ipapi.is gives the full picture with a free key. */
enum class Source(val key: String, val label: String, val site: String, val about: String) {
    IPWHO_IS(
        "ipwho", "ipwho.is", "https://ipwhois.io",
        "City, state, coordinates, network and time zone. 1,000 lookups a day.",
    ),
    FREEIPAPI(
        "freeipapi", "FreeIPAPI", "https://freeipapi.com",
        "City, state, coordinates, network and a proxy check. 60 lookups a minute.",
    ),
    IPAPI_IS(
        "ipapi", "ipapi.is", "https://ipapi.is",
        "With a free key: everything, plus VPN, proxy, Tor and hosting checks and abuse contacts (1,000 a day). " +
            "Without one: city, state and network only, 30 a day.",
    );

    companion object {
        fun fromKey(key: String?): Source? = entries.firstOrNull { it.key == key }
    }
}

/** One DNS answer, like an MX or TXT record. */
data class DnsRecord(val type: String, val name: String, val ttl: Long, val data: String)

/** What the internet registry has on file for the block an address belongs to (RDAP, the newer WHOIS). */
data class RdapInfo(
    val name: String? = null,
    val handle: String? = null,
    val range: String? = null,
    val cidr: String? = null,
    val type: String? = null,
    val country: String? = null,
    val registered: String? = null,
    val updated: String? = null,
    val status: String? = null,
    val registry: String? = null,
    val org: String? = null,
    val abuseEmail: String? = null,
    val parent: String? = null,
)

/** Something that loads on its own: still loading, there, or failed with a reason. */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/** A lookup the person made, as kept in History. */
data class HistoryEntry(
    val query: String,
    val ip: String,
    val place: String,
    val countryCode: String?,
    val provider: String?,
    val at: Long,
    val starred: Boolean = false,
)

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Match phone"),
    DAY("day", "Day chart"),
    NIGHT("night", "Night chart");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Null means automatic: try each source in turn until one answers. */
    val source: Source? = null,
    val saveHistory: Boolean = true,
    /** A free ipapi.is key, for the full answers (privacy checks, abuse contacts). Empty when there isn't one. */
    val ipapiKey: String = "",
) {
    /** The order to ask the services in. With an ipapi.is key, automatic asks ipapi.is first. */
    val order: List<Source>
        get() = when {
            source != null -> listOf(source) + Source.entries.filter { it != source }
            ipapiKey.isNotBlank() -> listOf(Source.IPAPI_IS) + Source.entries.filter { it != Source.IPAPI_IS }
            else -> Source.entries.toList()
        }
}
