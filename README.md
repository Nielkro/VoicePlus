# Voice Plus (PV2SVC)

Client-side bridge that lets you use **Simple Voice Chat (SVC)** on servers running **Plasmo Voice (PV)** — so SVC and PV players can hear each other on the same server.

## Features

- **Two-way audio bridge** between Simple Voice Chat and Plasmo Voice — Opus audio relayed over encrypted channels (AES/GCM on the SVC side, RSA/AES on the PV side).
- **Full player list in the SVC volume screen** — every player on the server shows up, including PV players, with working per-player volume sliders.
- **"No mod" indicator** — players without any voice mod are marked with the disabled icon, so you always know who can't hear you.
- **Client-side only** — works over the mod's normal networking plus a local UDP proxy; no server-side installation, admin access, or plugins required.
- **Performance-friendly** — all audio processing runs on background threads and never blocks the game's main thread.

## How it works

1. Your Simple Voice Chat connects to a local virtual server that Voice Plus spins up.
2. Voice Plus connects to the Plasmo Voice server on your behalf.
3. Mic audio is converted to the PV format and sent to the server.
4. Audio from other players is converted back and played through your SVC client.
5. Players without any voice mod are marked with an icon, so you can see who can't hear you.

## Requirements

- Minecraft **1.21.11** (Fabric)
- Fabric Loader **0.19.3+**
- **Fabric API**
- **Fabric Language Kotlin**
- **Simple Voice Chat** on your client
- **Plasmo Voice** on the server you join

## Usage

1. Install the mod in your client's `mods` folder.
2. Launch the game and join any server running Plasmo Voice.
3. Open the SVC volume/player screen — everyone on the server is listed, and voice chat just works.

## Building from source

```sh
./gradlew build
```

The built jar is placed in `build/libs/`.

## License

This project is licensed under [Apache License 2.0](LICENSE).