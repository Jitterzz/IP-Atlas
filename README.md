# IP Atlas

An Android app for looking up any IP address or domain and seeing everything about it, drawn on a chart of the
world in the style of a nautical chart.

- **Look up anything:** an IPv4 or IPv6 address, a domain, a full link, `host:port`, even an email address (it
  looks up the domain). Paste from the clipboard with one tap, share text to IP Atlas from any app, or select an
  address anywhere and pick **Look up IP** from the text selection menu.
- **Your connection:** your public IPv4 and IPv6 addresses, provider and location on the world chart, plus what the
  phone itself knows: Wi-Fi or mobile data, VPN on or off, local address, gateway, DNS servers, Private DNS,
  interface and MTU. It checks again whenever the connection changes.
- **The chart:** a close-up of where the address is, with latitude and longitude lines, country borders, nearby
  cities, a scale bar and the chart's magenta light on the spot. Zoom in and out, or open the spot in Maps.
- **Network:** provider, organization, ASN, route, range, network name, hostname (reverse DNS), domain, type of
  network, data center and registry.
- **Location and time:** city, region, postal code, country, continent, coordinates (decimal and degrees),
  accuracy, calling code, currency, and the time there now, its UTC offset, daylight saving, and how far ahead or
  behind you it is.
- **Privacy checks:** VPN, proxy, Tor exit, hosting, known abuse, mobile network, crawler and satellite, with abuse
  scores and the abuse contact (tap to email or call).
- **Registration:** what the internet registry (ARIN, RIPE NCC, APNIC, LACNIC or AFRINIC) has on file: network
  name, organization, range and block, type, dates, status and handle (RDAP, the newer WHOIS).
- **Domains:** every address a domain points to (tap one to look it up), aliases, mail servers, name servers,
  text records and certificate rules.
- **Address details:** decimal, hex, binary, old class, IPv6 full form, /64 network, interface ID, a MAC address
  built into the address, IPv4 inside 6to4/NAT64 addresses, Teredo clients, and the reverse DNS name.
- **Compare sources:** ask the other location services about the same address and see how far apart they are.
- **History:** every lookup, newest first, with stars to keep favorites at the top. Filter, copy or remove.
- **Day and night charts:** a light theme and a dark one, or follow the phone.

## Where the data comes from

All free, no accounts or keys. Lookups go straight from the phone to these services; IP Atlas has no server.

| What | Service |
| --- | --- |
| Location, network, privacy checks | [ipapi.is](https://ipapi.is) (1,000 a day), then [ipwho.is](https://ipwhois.io) and [FreeIPAPI](https://freeipapi.com) if it's busy. Pick the order in Settings. |
| Your public address | [ipify](https://www.ipify.org) |
| DNS (domains, hostnames) | Cloudflare DNS over HTTPS, with Google Public DNS as backup |
| Registration | RDAP through [rdap.org](https://rdap.org) |
| The charts (offline, built into the app) | GLOBE land mask, Natural Earth borders (public domain), GeoNames cities (CC BY 4.0) |

`tools/make_geodata.py` builds the chart data in `app/src/main/assets/geo`. `tools/preview.py` renders screenshot
previews from the app's own fonts, icons, colors and chart data.

## Fonts

[Barlow Semi Condensed](https://github.com/jpt/barlow) and [IBM Plex Mono](https://github.com/IBM/plex), both under
the SIL Open Font License 1.1. Their licenses ship in the app at `app/src/main/assets/licenses`.

## Building

The APK builds on GitHub: every push to `main` runs [`build-apk.yml`](.github/workflows/build-apk.yml) and puts the
APK on the **Releases** page (and with the workflow run). Builds are signed with the app's own key in `signing/`, so
each new APK installs over the last one and keeps your history.
