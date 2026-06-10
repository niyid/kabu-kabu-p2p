# Ghost Architecture: A Blueprint for Apps That Can't Betray Their Users

*A practical blueprint for serverless, surveillance-resistant mobile applications — illustrated by Kabu-Kabu and the Techducat ecosystem*

---

## The Problem With "Privacy" As a Marketing Label

Most apps that claim to be privacy-respecting still route your data through a central server owned by a company, in a jurisdiction with its own subpoena regime and data-retention laws. Even end-to-end encrypted messengers typically rely on central infrastructure to broker connections, store metadata, and manage keys. When that server is compelled, hacked, or sold, your privacy disappears.

A quieter movement has been building a different answer: what if the server simply did not exist?

This article explores a concrete engineering paradigm for building genuinely serverless, privacy-by-default mobile apps — one pioneered by a small developer studio based in Lagos, Nigeria, using two open-source primitives that have been battle-tested by the privacy community: the **I2P anonymity network** and **Monero (XMR)** privacy cryptocurrency. We use **Kabu-Kabu**, an open-source P2P ride-hailing app, as the primary technical case study, and we survey three other apps already live on Google Play that implement the same paradigm.

---

## The Two Primitives

### I2P — The Invisible Internet Project

I2P is a fully encrypted, peer-to-peer overlay network designed to resist traffic analysis. Unlike Tor, which is optimised for accessing the regular web ("clearnet"), I2P is built primarily for internal services — applications that communicate exclusively within the network. Its core mechanism, **garlic routing**, bundles multiple encrypted messages together (like cloves of garlic), making it significantly harder to correlate sender and receiver than Tor's onion routing.

The crucial property for mobile app developers is that I2P can be **embedded directly into an Android app** as a Java library. No separate app installation, no reliance on a VPN profile, no permission gymnastics. Your app *is* the I2P router. When the user opens the app, a fully functional I2P node starts on-device, connects to the network over the standard internet connection, and all subsequent peer communication is anonymised automatically.

### Monero — Privacy by Protocol

Monero is a cryptocurrency in which privacy is mandatory, not optional. Every transaction uses:

* **Ring signatures** — the true sender is hidden among a group of decoy signers
* **Stealth addresses** — one-time addresses prevent linking outputs to a recipient's public key
* **RingCT (Confidential Transactions)** — transaction amounts are hidden by default

Critically, Monero also supports **multisignature (multisig) wallets**, where funds can be locked under joint control of multiple parties and only released when a threshold of signatures is provided. This makes it possible to build trustless financial arrangements — escrow, savings circles, peer betting — without a central custodian holding the funds.

---

## The Paradigm: Five Design Principles

The apps covered in this article share a coherent set of design decisions that together constitute a replicable paradigm for privacy-first mobile development.

**1. No central server.** All communication happens between devices over I2P. There is no relay server, no cloud database, no API endpoint belonging to the developer. The developer cannot be compelled to hand over user data because they hold none.

**2. On-device identity through one-way hashing.** User identifiers — typically a phone number or device ID — are SHA-256 hashed on-device before ever being used as a network identifier. The plaintext never leaves the phone. SHA-256 is a one-way function: the hash can identify a peer on the network without revealing the underlying credential.

**3. Fuzzy location, not precise GPS.** Where location is needed (ride-matching, local safety alerts), raw GPS coordinates are immediately converted to a **GeoHash cell** — typically GeoHash level 5, which corresponds to a cell roughly 4.9 km × 4.9 km. The raw latitude/longitude is discarded and never transmitted. Peers can discover each other within the same geographic zone without pinpointing each other's exact position.

**4. Local-only persistence.** Trip records, incident reports, skill listings, and trade states are stored in on-device databases (Android Room / SQLite). No cloud backup. This is enforced at the manifest level via Android's `backup_rules.xml` and `data_extraction_rules.xml` to prevent automatic Google backup from exfiltrating data.

**5. Monero for financial flows.** Where the app handles money, Monero's multisig capability is used to ensure the developer never holds user funds, and transactions are private by protocol rather than by policy.

---

## Case Study: Kabu-Kabu — Privacy-First P2P Ride-Hailing

[Kabu-Kabu](https://github.com/niyid/kabu-kabu-p2p) is an open-source Android app for peer-to-peer taxi and courier matching, explicitly designed to replace centralised ride-hailing platforms like Uber or Bolt with a serverless alternative.

### What traditional ride-hailing apps do

Every major ride-hailing platform is architecturally a surveillance system. Exact GPS coordinates are continuously uploaded to a central server. Your real phone number is stored and linked to every trip. The company can reconstruct your location history, your travel patterns, and your social graph from this data. Third-party data brokers often receive a share of it.

### What Kabu-Kabu does instead

| Concern | Traditional approach | Kabu-Kabu approach |
|----|----|----|
| Location data | Exact GPS uploaded to server | GeoHash-5 only; raw coords discarded immediately |
| Identity | Real phone number stored in DB | SHA-256 hash on-device; plaintext never leaves |
| Network traffic | Routes through company relay | All peer traffic via I2P garlic routing |
| Trip history | Stored in cloud database indefinitely | On-device Room database; retained 30 days |
| Data sharing | Shared with advertisers / brokers | No server, no ads, no data brokers |

### Technical architecture

Kabu-Kabu's architecture illustrates how the paradigm is implemented in practice:

```
Rider/Driver device
├── MainActivity / DriverActivity
│     └── I2PKabuClient  ──TCP(127.0.0.1:8881)──▶
│                                                  I2PKabuService (foreground)
│                                                  ├── EmbeddedI2PRouter
│                                                  ├── Local TCP bridge
│                                                  └── KabuDatabase (Room)
│
└── Privacy layer:
      GeoHash-5 only (no raw GPS over network)
      SHA-256 device identity (no plaintext phone)
      I2P garlic routing (no IP exposure)
      No cloud backup
```

The `EmbeddedI2PRouter` manages the full I2P router lifecycle as a foreground Android service. The `I2PKabuClient` connects to it via a local TCP socket, making the I2P layer invisible to the application logic above it. Ride requests are broadcast to peers within the same GeoHash cell; once a driver accepts, a direct I2P tunnel is established for the trip itself.

The tech stack is deliberately mainstream: Kotlin, Hilt for dependency injection, Android Room for persistence, OsmDroid for offline maps (no Google Maps API key required). The only exotic dependency is the embedded I2P router JARs. This means any competent Android developer can read, audit, and fork the codebase.

The roadmap includes XMR payment integration via an on-device Monero wallet — completing the financial privacy layer — and an F-Droid reproducible build for users who do not want to use Google Play at all.

### Why this matters for ride-hailing specifically

Ride-hailing data is among the most sensitive location data that exists. It records where you live (trip origins cluster at home), where you work, where you worship, who you visit, and when. In jurisdictions with authoritarian governance or where certain activities carry legal or social risk, this data can be used against users. Kabu-Kabu's architecture means this data simply does not exist outside the user's own device.

---

## The Ecosystem: Three Apps Already Live on Google Play

Kabu-Kabu is the newest member of an already-deployed ecosystem of apps by **Techducat Team** that implement the same I2P/Monero paradigm across five distinct use cases.

---

### Buzzr — P2P Community Safety Alerts

**[Buzzr](https://play.google.com/store/apps/details?id=com.techducat.buzzr)** turns each phone into a community safety node. When something happens nearby — an accident, a fire, a robbery, a flood — a user reports it instantly. The incident is broadcast to nearby peers over I2P, categorised (FIRE · ACCIDENT · ROBBERY · FLOOD · FIGHT), and stored locally for 48 hours. Peers can rebroadcast alerts to extend their reach.

The privacy architecture is identical to Kabu-Kabu's: SHA-256 phone hash for identity, GeoHash-5 for geographic zone matching, embedded I2P for transport. No account is required. No data is shared with third parties. The Play Store data safety declaration confirms zero data collection.

Buzzr works anywhere peers are present. It has crossed 1,000+ installs on Google Play, making it the most-deployed app in the ecosystem.

**Developer note on the I2P substrate:** Buzzr's internal codebase is called `buzzr-p2p` and uses an `I2PBuzzrService` on port 8880. Kabu-Kabu's `I2PKabuService` runs on port 8881. The two apps share the same underlying I2P P2P architecture, differing only in their domain logic — incident broadcasting versus ride-matching. This reusability is a key advantage of the paradigm: once the I2P substrate is built and tested, new privacy-preserving applications can be built on top of it with relatively modest additional effort.

---

### 6°Net — Privacy-First P2P Skill Networking

**[6°Net](https://play.google.com/store/apps/details?id=com.techducat.favornet)** is a peer-to-peer professional network inspired by the six degrees of separation theory. It lets users search for skills — web developer, electrician, accountant, plumber — across 2, 5, or 8 hops in their existing contact graph, surfacing trusted talent through mutual connections rather than through a centralised platform.

The privacy model here is particularly elegant. Your contact list — one of the most sensitive data categories on a phone — is SHA-256 hashed entirely on-device. Only anonymised peer IDs ever leave the phone. The I2P network routes search queries to matching peers; results arrive in real time from within your social graph without any central platform ever learning who you searched for or what skills you need.

Three search depths are offered: LITE (2°, tight trusted circle), MID (5°, broader professional network), and DEEP (8°, maximum reach for rare skills). Post-engagement star ratings propagate across the P2P network as a reputation layer, again without any central database.

6°Net's recent update removed Google Ads, Firebase, and all relay-server dependencies — transitioning to a fully serverless architecture. The changelog notes: "All communication routes over an embedded I2P router, peer-to-peer with no central server. Runs in the background and restarts on boot, keeping you reachable when the app is closed."

This is notable because it represents a developer actively unwinding dependencies on surveillance-capitalism infrastructure in favour of the privacy paradigm described in this article.

---

### ScrowIt! — XMR Escrow

**[ScrowIt!](https://play.google.com/store/apps/details?id=com.techducat.scrowit)** is where the Monero half of the paradigm comes fully into view. It is a non-custodial peer-to-peer escrow app for Monero trades between strangers.

The mechanism is Monero's **2-of-3 multisig**: funds are locked in a wallet jointly controlled by Buyer, Seller, and a neutral Arbitrator. No single party can move the funds alone — two of the three signatures are always required. The developer never touches the funds.

The workflow:

1. Seller creates a trade and invites an Arbitrator via QR code
2. Buyer funds the multisig escrow wallet with XMR
3. Seller delivers goods or services
4. Buyer confirms — funds are released automatically
5. If there is a dispute, the Arbitrator casts the deciding vote

All peer communication is tunnelled through I2P — no counterparty ever learns another's IP address. Local storage is AES-256 encrypted via Android Keystore. Each user gets a unique Ed25519 node key. Messages are signed, preventing impersonation.

For situations where a counterparty is offline, an end-to-end encrypted Firestore mailbox acts as a store-and-forward relay — a pragmatic concession to the realities of mobile connectivity that does not compromise the privacy of message contents.

The fee is 1.5% collected at payout — transparent, atomic, and disclosed up front.

ScrowIt! demonstrates that the I2P/Monero paradigm is viable for financial applications where traditional fintech would require KYC, bank accounts, and centralised custody.

---

### Àjọ — Monero Savings Circle

**[Àjọ](https://play.google.com/store/apps/details?id=com.techducat.ajo)** is a decentralised Rotating Savings and Credit Association (ROSCA) app — a digital implementation of the traditional savings-circle model known in various cultures as tontine, chit fund, or esusu, where a group of trusted people pool regular contributions so each member receives a lump sum in turn.

The privacy architecture takes an interesting step beyond ScrowIt!: because a savings circle involves multiple participants over multiple rounds, the identity challenge is harder. Àjọ solves it by eliminating accounts entirely — there are no email addresses, no phone numbers, no usernames. Identity lives only on the device as a cryptographic key. Contributions and payouts run over Monero, making them unlinkable by outside parties via ring signatures and stealth addresses.

The multisig implementation here is generalised: rather than a fixed 2-of-3 scheme, Àjọ uses **(n-1)-of-n Monero multisig** where n is the group size (3–20 members). No single member — including the group creator — can move funds unilaterally. Every payout requires consensus from the group, protecting members against exit scams by dishonest organisers.

Three payout methods are supported: a fixed pre-agreed sequence, a verifiable random lottery each round, or a bidding mechanism where the lowest bid wins early access to the round's payout — replicating the more sophisticated dynamics found in traditional savings circles.

All peer synchronisation between group members runs over I2P. No cloud database holds the group state. The Play Store data safety declaration confirms zero data collected and zero data shared with third parties.

Àjọ is the most socially complex app in the ecosystem — it coordinates financial consensus among up to 20 anonymous peers across multiple rounds, entirely over an anonymous P2P network, without any central coordinator. That it works at all is a meaningful proof of what the paradigm can handle.

---

### Versuz — P2P Skill Betting

**[Versuz](https://play.google.com/store/apps/details?id=com.techducat.versuz)** applies the same non-custodial Monero multisig mechanism to peer-to-peer skill challenges and bets. Two parties — a Challenger and an Opponent — lock their stakes into a 2-of-3 multisig escrow wallet with a neutral Arbitrator as the third key holder. The outcome is verified, the winner's signature plus the Arbitrator's releases the funds. No bookie, no platform, no house edge beyond a transparent 1.5% service fee collected atomically at payout.

Versuz is positioned for gamers, esports competitors, chess players, and sports fans — anyone who wants a trustless way to settle skill-based challenges without trusting a centralised betting platform that holds their funds, knows their identity, and operates under a licence that can be revoked.

The network privacy stack is identical to the rest of the ecosystem: all peer communication tunnelled through an embedded I2P router, Ed25519 node keys for pseudonymous identity, AES-256 encrypted local storage via Android Keystore, and no accounts or KYC of any kind. Play Store data safety: zero data collected, zero data shared.

The dispute resolution flow mirrors ScrowIt!'s: either party can raise a dispute, the pre-agreed Arbitrator reviews evidence and rules, and the loser's stake is released to the winner. No single party can freeze or steal funds at any point in the process.

Versuz is rated 18+ on Google Play given its gambling-adjacent nature. The app itself carries a disclaimer noting that Monero transactions are irreversible and users are responsible for compliance with local laws — an honest acknowledgement that pseudonymous betting tools exist in a complex legal landscape.

---

## Threat Model: What This Paradigm Defends Against

The I2P/Monero paradigm provides strong protections against a specific set of threats:

| Threat | Defence |
|----|----|
| Server seizure / legal compulsion | No server exists; developer holds no data |
| IP address correlation | I2P garlic routing hides source IP |
| Identity inference from phone number | SHA-256 hash is one-way; plaintext never transmitted |
| Location history reconstruction | GeoHash-5 provides \~5 km resolution; raw GPS never stored |
| Financial surveillance | Monero transactions are private by protocol |
| App store data harvesting | On-device Room DB; cloud backup disabled |
| Man-in-the-middle attacks | I2P provides end-to-end encryption; Ed25519 message signing |

What it does **not** protect against: a device-level compromise (malware with root access can read the local database), physical seizure of a device, or coercion of the individual user. No software architecture defeats a physical threat model.

---

## For Developers: How to Replicate This Paradigm

The Kabu-Kabu codebase is open source and designed to be forked. The core steps for building a new app on this substrate:

**Step 1 — Embed the I2P router.** Obtain `router.jar`, `sam.jar`, and `i2p-android-client.aar` and place them in `app/libs/`. The `EmbeddedI2PRouter.kt` class handles router lifecycle. The SAM (Simple Anonymous Messaging) bridge provides a standard socket API so application code talks to I2P via ordinary TCP — no I2P internals need to be exposed to your app logic.

**Step 2 — Define your GeoHash zone.** Convert user GPS coordinates to a GeoHash-5 string immediately on receipt using a library like `ch.hsr:geohash`. Use this hash as the "zone" for peer discovery and message routing. Discard raw coordinates.

**Step 3 — Hash your identity.** Hash the user's phone number (or any persistent device identifier) with SHA-256 on first launch. Store the hash; discard the plaintext. Use the hash as your node's identity on the I2P network.

**Step 4 — Define your domain messages.** Kabu-Kabu uses `RideRequest` and `DriverOffer`; Buzzr uses `IncidentReport`; 6°Net uses `SkillListing` and `SearchQuery`. Model your app's domain objects, serialise them to JSON, and broadcast/receive them over the I2P socket.

**Step 5 — Disable cloud backup.** Add `android:allowBackup="false"` to your manifest and configure `backup_rules.xml` to exclude your Room database. This prevents Android's automatic backup from silently uploading your on-device data to Google's servers.

**Step 6 — Add Monero if money is involved.** If your app handles payments, integrate the Monero Android wallet library and use 2-of-3 multisig for any escrow or joint-custody scenario. This keeps your app non-custodial by design.

Build flavours for `playstore` and `fdroid` are recommended from the start — F-Droid users are exactly the audience this paradigm serves, and a reproducible build demonstrates that the binary matches the published source.

---

## Conclusion

The apps described in this article — Kabu-Kabu, Buzzr, 6°Net, ScrowIt!, Àjọ, and Versuz — represent something genuinely new in the Android ecosystem: a coherent, replicable paradigm for building applications where privacy is an architectural property rather than a policy claim.

The paradigm is not theoretical. It is running on Google Play today, serving real users, with open-source code available for inspection. It does not require exotic hardware, custom operating systems, or deep cryptographic expertise. It requires two mature open-source primitives (I2P and Monero), a handful of well-understood Android libraries, and the discipline to never write user data to a server you control.

The most remarkable thing about this approach is its simplicity as a **trust model**: the developer cannot betray their users' data because they never had it.

That is the paradigm. The code is available. The question is how many more apps will be built on it.

---

*Kabu-Kabu source code: [github.com/niyid/kabu-kabu-p2p](https://github.com/niyid/kabu-kabu-p2p)*

*Buzzr on Google Play: [play.google.com/store/apps/details?id=com.techducat.buzzr](https://play.google.com/store/apps/details?id=com.techducat.buzzr)*

*6°Net on Google Play: [play.google.com/store/apps/details?id=com.techducat.favornet](https://play.google.com/store/apps/details?id=com.techducat.favornet)*

*ScrowIt! on Google Play: [play.google.com/store/apps/details?id=com.techducat.scrowit](https://play.google.com/store/apps/details?id=com.techducat.scrowit)*

*Àjọ on Google Play: [play.google.com/store/apps/details?id=com.techducat.ajo](https://play.google.com/store/apps/details?id=com.techducat.ajo)*

*Versuz on Google Play: [play.google.com/store/apps/details?id=com.techducat.versuz](https://play.google.com/store/apps/details?id=com.techducat.versuz)*
