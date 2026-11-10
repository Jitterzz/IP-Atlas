package app.ipatlas

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ipatlas.data.DeviceNet
import app.ipatlas.data.DeviceNetwork
import app.ipatlas.data.Dns
import app.ipatlas.data.DnsRecord
import app.ipatlas.data.Geo
import app.ipatlas.data.HistoryEntry
import app.ipatlas.data.Http
import app.ipatlas.data.IpAddress
import app.ipatlas.data.IpInfo
import app.ipatlas.data.Load
import app.ipatlas.data.LookupError
import app.ipatlas.data.NoSuchDomain
import app.ipatlas.data.Providers
import app.ipatlas.data.Query
import app.ipatlas.data.Queries
import app.ipatlas.data.Rdap
import app.ipatlas.data.RdapInfo
import app.ipatlas.data.Resolved
import app.ipatlas.data.Source
import app.ipatlas.data.Store
import app.ipatlas.data.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/** The screens, as a stack: the last one shows, and Back takes it off. */
sealed interface Screen {
    data object Main : Screen
    data class Result(val state: ResultState) : Screen
}

enum class Tab { LOOKUP, HISTORY, SETTINGS }

enum class Phase { RESOLVING, LOCATING, DONE, LOCAL, FAILED }

/** One lookup on screen: a domain or an address, and everything found out about it so far. */
class ResultState(val id: Long, val input: String, val query: Query) {
    val host: String? = (query as? Query.Host)?.name
    var resolved by mutableStateOf<Resolved?>(null)
    var selected by mutableStateOf((query as? Query.Address)?.ip)
    var phase by mutableStateOf(if (query is Query.Host) Phase.RESOLVING else Phase.LOCATING)
    var info by mutableStateOf<IpInfo?>(null)
    var notes by mutableStateOf<List<String>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var ptr by mutableStateOf<Load<String?>>(Load.Idle)
    var rdap by mutableStateOf<Load<RdapInfo>>(Load.Idle)
    var records by mutableStateOf<Load<Map<String, List<DnsRecord>>>>(Load.Idle)
    var compare by mutableStateOf<Load<List<Compared>>>(Load.Idle)
    internal var ipJob: Job? = null
    internal var recordsJob: Job? = null

    /** What History and the star go by: the domain, or the address. */
    val key: String? get() = host ?: selected?.toString()
}

/** Another service's answer for the same address, and how far its location is from the main one. */
data class Compared(val source: Source, val info: IpInfo?, val error: String?, val km: Double?)

/** The phone's own public address, and what the services say about it. */
class SelfState {
    var v4 by mutableStateOf<Load<String>>(Load.Idle)
    var v6 by mutableStateOf<Load<String>>(Load.Idle)
    var info by mutableStateOf<Load<IpInfo>>(Load.Idle)

    val primary: String? get() = (v4 as? Load.Ready)?.value ?: (v6 as? Load.Ready)?.value
    val loading: Boolean get() = v4 == Load.Loading || info == Load.Loading
}

class AppModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)

    val stack = mutableStateListOf<Screen>(Screen.Main)
    var tab by mutableStateOf(Tab.LOOKUP)
    var search by mutableStateOf("")
    var searchError by mutableStateOf<String?>(null)

    val self = SelfState()
    var device by mutableStateOf(DeviceNet.NONE)
        private set

    private var nextId = 1L
    private var selfJob: Job? = null
    private var readJob: Job? = null
    private var selfIdentity: String? = null
    private val stopWatching: () -> Unit

    init {
        Http.userAgent = "IPAtlas/${BuildConfig.VERSION_NAME} (Android; davidanddev.com)"
        device = DeviceNetwork.read(app)
        stopWatching = DeviceNetwork.watch(app) { onNetworkChanged() }
        refreshSelf()
    }

    override fun onCleared() {
        stopWatching()
        super.onCleared()
    }

    // ---- The phone's connection

    private fun onNetworkChanged() {
        viewModelScope.launch(Dispatchers.Main) {
            readJob?.cancel()
            readJob = viewModelScope.launch {
                delay(400)
                val fresh = withContext(Dispatchers.IO) { DeviceNetwork.read(getApplication<Application>()) }
                device = fresh
                // A new network (or a VPN going on or off) usually means a new public address.
                if (fresh.identity != selfIdentity) {
                    delay(1200)
                    refreshSelf()
                }
            }
        }
    }

    fun refreshSelf() {
        selfJob?.cancel()
        selfIdentity = device.identity
        selfJob = viewModelScope.launch {
            self.v4 = Load.Loading
            self.v6 = Load.Loading
            self.info = Load.Loading
            val (v4, v6) = supervisorScope {
                val a = async { ownAddress("https://api.ipify.org?format=json") }
                val b = async { ownAddress("https://api6.ipify.org?format=json") }
                attempt { a.await() } to attempt { b.await() }
            }
            self.v4 = v4.fold({ Load.Ready(it) }, { Load.Failed("No IPv4 address found") })
            self.v6 = v6.fold({ Load.Ready(it) }, { Load.Failed("No IPv6 address") })
            val primary = v4.getOrNull() ?: v6.getOrNull()
            self.info = try {
                val located = Providers.locate(primary, store.settings.order)
                // If ipify couldn't be reached, the location service still saw our address.
                if (primary == null) {
                    val ip = IpAddress.parse(located.info.ip)
                    if (ip?.version == 4) self.v4 = Load.Ready(ip.toString()) else if (ip != null) self.v6 = Load.Ready(ip.toString())
                }
                Load.Ready(located.info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Failed(
                    if (!device.connected) "This phone isn't connected to the internet right now." else e.message ?: "Couldn't look up your connection.",
                )
            }
        }
    }

    private suspend fun ownAddress(url: String): String {
        val ip = Http.json(url).str("ip")?.let { IpAddress.parse(it) }
        return ip?.toString() ?: throw LookupError("ipify sent back no address.")
    }

    // ---- Lookups

    /** Looks up what's in the search box (or [text]). Shows why it can't, under the box, when it can't. */
    fun submit(text: String = search) {
        val q = Queries.parse(text)
        if (q is Query.Invalid) {
            searchError = q.message
            return
        }
        searchError = null
        open(text.trim(), q)
    }

    fun openMine() {
        val ip = self.primary ?: return
        IpAddress.parse(ip)?.let { open(ip, Query.Address(it)) }
    }

    fun openEntry(entry: HistoryEntry) {
        val q = Queries.parse(entry.query)
        if (q !is Query.Invalid) open(entry.query, q)
    }

    private fun open(input: String, q: Query) {
        val r = ResultState(nextId++, input, q)
        stack.add(Screen.Result(r))
        start(r)
    }

    fun retry(r: ResultState) {
        val ip = r.selected
        if (r.host != null && r.resolved == null) start(r) else if (ip != null) lookupIp(r, ip) else start(r)
    }

    private fun start(r: ResultState) {
        when (val q = r.query) {
            is Query.Address -> lookupIp(r, q.ip)
            is Query.Invalid -> {
                r.phase = Phase.FAILED
                r.error = q.message
            }
            is Query.Host -> {
                r.ipJob?.cancel()
                r.ipJob = viewModelScope.launch {
                    r.phase = Phase.RESOLVING
                    r.error = null
                    try {
                        val res = Dns.resolve(q.name)
                        r.resolved = res
                        loadRecords(r)
                        val first = res.addresses.firstOrNull()
                        if (first == null) {
                            r.phase = Phase.FAILED
                            r.error = "${q.name} exists, but it doesn't point to any IP address."
                        } else {
                            runIp(r, first)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: NoSuchDomain) {
                        r.phase = Phase.FAILED
                        r.error = e.message
                    } catch (e: Exception) {
                        r.phase = Phase.FAILED
                        r.error = "Couldn't find the addresses for ${q.name}: the DNS lookup ${Http.reason(e)}."
                    }
                }
            }
        }
    }

    /** Switches a domain's result to another of its addresses. */
    fun select(r: ResultState, ip: IpAddress) {
        if (r.selected == ip && r.phase != Phase.FAILED) return
        lookupIp(r, ip)
    }

    private fun lookupIp(r: ResultState, ip: IpAddress) {
        r.ipJob?.cancel()
        r.ipJob = viewModelScope.launch { runIp(r, ip) }
    }

    private suspend fun runIp(r: ResultState, ip: IpAddress): Unit = coroutineScope {
        r.selected = ip
        r.info = null
        r.notes = emptyList()
        r.error = null
        r.compare = Load.Idle
        val target = ip.lookupTarget
        if (target == null) {
            r.phase = Phase.LOCAL
            r.ptr = Load.Idle
            r.rdap = Load.Idle
            remember(r, null)
            return@coroutineScope
        }
        r.phase = Phase.LOCATING
        r.ptr = Load.Loading
        r.rdap = Load.Loading
        launch { r.ptr = load("Reverse DNS") { Dns.reverse(ip) } }
        launch { r.rdap = load("The registry") { Rdap.lookup(target) } }
        try {
            val located = Providers.locate(target.toString(), store.settings.order)
            r.info = located.info
            r.notes = located.notes
            r.phase = Phase.DONE
            remember(r, located.info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            r.phase = Phase.FAILED
            r.error = e.message ?: "Couldn't look up $target."
        }
    }

    private fun loadRecords(r: ResultState) {
        val host = r.host ?: return
        r.recordsJob?.cancel()
        r.records = Load.Loading
        r.recordsJob = viewModelScope.launch { r.records = load("DNS") { Dns.records(host) } }
    }

    /** Asks the other services about the same address, to see where they agree. */
    fun compare(r: ResultState) {
        val info = r.info ?: return
        val target = r.selected?.lookupTarget ?: return
        r.compare = Load.Loading
        viewModelScope.launch {
            val others = Source.entries.filter { it != info.source }
            val answers = supervisorScope {
                others.map { s -> async { s to attempt { Providers.lookup(s, target.toString()) } } }.map { it.await() }
            }
            r.compare = Load.Ready(
                answers.map { (s, res) ->
                    val other = res.getOrNull()
                    val km = if (other?.latitude != null && other.longitude != null && info.latitude != null && info.longitude != null) {
                        Geo.distanceKm(info.latitude, info.longitude, other.latitude, other.longitude)
                    } else {
                        null
                    }
                    Compared(s, other, res.exceptionOrNull()?.let { "${s.label} ${Http.reason(it)}." }, km)
                },
            )
        }
    }

    private fun remember(r: ResultState, info: IpInfo?) {
        val ip = r.selected ?: return
        val key = r.key ?: return
        store.record(
            HistoryEntry(
                query = key,
                ip = ip.toString(),
                place = info?.place?.ifEmpty { null } ?: ip.kind.label + " address",
                countryCode = info?.countryCode,
                provider = info?.provider,
                at = System.currentTimeMillis(),
            ),
        )
    }

    fun isStarred(r: ResultState): Boolean = r.key?.let { store.isStarred(it) } ?: false

    fun toggleStar(r: ResultState) {
        val key = r.key ?: return
        val ip = r.selected?.toString() ?: ""
        val entry = HistoryEntry(key, ip, r.info?.place ?: "", r.info?.countryCode, r.info?.provider, System.currentTimeMillis())
        store.setStarred(entry, !store.isStarred(key))
    }

    // ---- Moving around

    val canGoBack: Boolean get() = stack.size > 1 || tab != Tab.LOOKUP

    fun back(): Boolean {
        if (stack.size > 1) {
            val top = stack.removeAt(stack.lastIndex)
            if (top is Screen.Result) {
                top.state.ipJob?.cancel()
                top.state.recordsJob?.cancel()
            }
            return true
        }
        if (tab != Tab.LOOKUP) {
            tab = Tab.LOOKUP
            return true
        }
        return false
    }

    /** Text shared to the app, or picked from "Look up IP" in another app's text selection menu. */
    fun handleIncoming(text: String) {
        val found = Queries.findIn(text) ?: text.trim().take(200)
        while (stack.size > 1) back()
        tab = Tab.LOOKUP
        search = found
        submit(found)
    }

    // ---- Sharing

    fun shareText(r: ResultState): String {
        val ip = r.selected
        val info = r.info
        val lines = ArrayList<String>()
        lines += "IP Atlas: ${r.key ?: r.input}"
        if (ip != null) lines += "Address: $ip (IPv${ip.version}, ${ip.kind.label.lowercase()})"
        if (info != null) {
            if (info.place.isNotEmpty()) lines += "Location: ${info.place}"
            if (info.latitude != null && info.longitude != null) lines += "Coordinates: ${Geo.decimal(info.latitude, info.longitude)}"
            info.timeZone?.let { lines += "Time zone: $it" }
            info.provider?.let { lines += "Provider: $it" }
            if (info.asn != null) lines += "Network: AS${info.asn}" + (info.org ?: info.asnName)?.let { " $it" }.orEmpty() + (info.route ?: info.network)?.let { ", $it" }.orEmpty()
        }
        (r.ptr as? Load.Ready)?.value?.let { lines += "Hostname: $it" }
        if (info != null) {
            val checks = listOf("VPN" to info.isVpn, "proxy" to info.isProxy, "Tor" to info.isTor, "hosting" to info.isHosting)
                .filter { it.second != null }
                .joinToString(", ") { "${it.first} ${if (it.second == true) "yes" else "no"}" }
            if (checks.isNotEmpty()) lines += "Privacy checks: $checks"
            (info.abuseEmail ?: (r.rdap as? Load.Ready)?.value?.abuseEmail)?.let { lines += "Abuse contact: $it" }
            lines += "Data: ${info.source.label}"
        }
        return lines.joinToString("\n")
    }

    // ---- Helpers

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun <T> load(what: String, block: suspend () -> T): Load<T> = try {
        Load.Ready(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed("$what ${Http.reason(e)}.")
    }
}
