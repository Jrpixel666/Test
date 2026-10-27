# Security Tools

A built-in panel for staying safe on networks you don't control — airports, hotels, cafés, conferences.
Open it from the home long-press menu (**security tools**) or from **settings → security tools**.

It has two halves:

## 1. The searchable tools database

A curated catalogue of *defensive* utilities, grouped the way a traveller looks for them:

| Category        | What it's for                                                        |
| --------------- | -------------------------------------------------------------------- |
| network         | map and probe the networks you're on (scanners, ping/traceroute)     |
| wifi            | see the airwaves and the access points around you                    |
| bluetooth       | scan and inspect nearby Bluetooth / BLE devices                      |
| mitm self-test  | catch anything sitting between you and the internet                  |
| privacy & vpn   | encrypt your traffic and your DNS on untrusted networks              |

- **Search** matches names, summaries, categories and package names.
- Tools you've **already installed** are highlighted green and float to the top; the
  **installed only** filter shows just those — the "tools I installed" view.
- Each tool has **open** (if installed), **install** (opens its F-Droid or Play listing), and
  **info** (its project page). Entries with no app (e.g. `nmap` in Termux, `badssl.com`) are
  reference links.

The catalogue lives in [`app/src/main/kotlin/dev/omalauncher/security/SecurityCatalog.kt`](../app/src/main/kotlin/dev/omalauncher/security/SecurityCatalog.kt).
It's plain Kotlin with no Android dependencies, so adding a tool is one `SecurityTool(...)` line and
the search/sort logic is covered by `SecurityCatalogTest`.

## 2. The man-in-the-middle self-test

A **defensive** check: it looks at how *this* device is connected right now and reports anything that
could let someone read or alter your traffic. It never touches anyone else's device and sends nothing
off the phone — this is interception *detection*, not interception.

It flags:

- **extra root certificates** in the system trust store (the single biggest sign HTTPS can be
  intercepted) — an **alert**;
- a configured **network proxy**, a **captive portal**, or being on **wifi without a VPN** — **warnings**;
- whether a **VPN** is up and whether **DNS is encrypted** (Private DNS) — notes.

The decision logic is pure Kotlin in
[`MitmSelfTest.kt`](../app/src/main/kotlin/dev/omalauncher/security/MitmSelfTest.kt) (tested by
`MitmSelfTestTest`); the UI in `ui/SecurityScreen.kt` only gathers the facts from `ConnectivityManager`
and the `AndroidCAStore` and renders the findings. Reading them needs `ACCESS_NETWORK_STATE`, a normal
permission granted at install.
