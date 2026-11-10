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
) {
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

/** Where location data comes from. Each one is free and needs no account. */
enum class Source(val key: String, val label: String, val site: String, val about: String) {
    IPAPI_IS(
        "ipapi", "ipapi.is", "https://ipapi.is",
        "Location, network, privacy checks (VPN, proxy, Tor, hosting) and abuse contacts. 1,000 lookups a day.",
    ),
    IPWHO_IS(
        "ipwho", "ipwho.is", "https://ipwhois.io",
        "Location, network and time zone. 1,000 lookups a day.",
    ),
    FREEIPAPI(
        "freeipapi", "FreeIPAPI", "https://freeipapi.com",
        "Location, network and a proxy check. 60 lookups a minute.",
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
) {
    /** The order to ask the services in. */
    val order: List<Source>
        get() = if (source == null) Source.entries.toList() else listOf(source) + Source.entries.filter { it != source }
}
