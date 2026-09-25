# Voice Plus (PV2SVC)

A **client-side bridge** that lets you use **Simple Voice Chat (SVC)** on servers running **Plasmo Voice (PV)** — and lets SVC and PV players hear each other on the same server.

![Fabric](https://img.shields.io/badge/modloader-Fabric-1976d2) ![Minecraft](https://img.shields.io/badge/minecraft-1.21.4%20--%2026.3-4caf50) ![Client](https://img.shields.io/badge/side-Client-9c27b0)

---

## What does it do?

Servers that use Plasmo Voice only allow **Plasmo Voice players** to talk. If you use Simple Voice Chat, you simply can't use voice chat there. **Voice Plus** fixes that without any server-side changes:

- The mod connects to the server **as a Plasmo Voice client** (TCP handshake + encrypted UDP voice).
- It runs a **local UDP proxy** on `127.0.0.1` that your Simple Voice Chat client connects to.
- Voice audio is translated between the two protocols in real time, so **PV players and you (SVC) can talk to each other**.
- You keep the familiar **SVC interface**: the volume/player list screen, player icons and the ability to adjust per-player volume.

It's **fully client-side** — install it on your client and you're done. The server doesn't need this mod.

---

## Requirements

- **Fabric API**
- **Fabric Language Kotlin**
- **Simple Voice Chat** on your client
- **Plasmo Voice** on the server you join

---

## How it works

1. Your Simple Voice Chat connects to a local virtual server that Voice Plus spins up.
2. Voice Plus connects to the Plasmo Voice server on your behalf.
3. Mic audio is converted to the Plasmo Voice format and sent to the server.
4. Audio from other players is converted back and played through your Simple Voice Chat client.

---

## Telemetry & Privacy

VoicePlus collects **100% anonymous, stateless telemetry data** solely to understand mod usage and prioritize Minecraft version support. 

- **Collected Data:** Minecraft version, Mod version, Java version, OS family (Linux/Windows/macOS), voice session duration bucket (`<5m`, `5-15m`, `15m-1h`, `1-3h`, `>3h`), and SOCKS5 bypass usage flag.
- **Privacy Guarantee:** No player usernames, UUIDs, session tokens, IP addresses, server addresses, hardware IDs, or audio data are ever collected or logged. No persistent device identifiers are stored.
- **Opt-Out:** You can disable telemetry at any time by setting `enableMetrics=false` in `config/voiceplus.properties` or via the in-game settings screen.

---

## Limitations

- Requires both SVC (client) and Plasmo Voice (server) to be installed — it bridges the two, it doesn't replace them.
- Voice features that exist only in one protocol (e.g. server-side add-ons for Simple Voice Chat) are limited to what SVC supports.

---

## Building from source

```sh
./gradlew chiseledBuild
```

The built jars for all supported versions (1.21.4, 1.21.11, 26.1, 26.2, 26.3) are placed in `versions/<version>/build/libs/`.

---

## License

This project is licensed under the [Apache License 2.0](LICENSE).
