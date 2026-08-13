# Voice Plus (PV2SVC)

Client-side bridge that lets you use **Simple Voice Chat (SVC)** on servers running **Plasmo Voice (PV)** — so SVC and PV players can hear each other on the same server.

## Features

- **Two-way audio bridge** between Simple Voice Chat and Plasmo Voice — Opus audio relayed over encrypted channels (AES/GCM on the SVC side, RSA/AES on the PV side).

## How it works

1. Your Simple Voice Chat connects to a local virtual server that Voice Plus spins up.
2. Voice Plus connects to the Plasmo Voice server on your behalf.
3. Mic audio is converted to the PV format and sent to the server.
4. Audio from other players is converted back and played through your SVC client.
5. Players without any voice mod are marked with an icon, so you can see who can't hear you.

## Requirements
- **Fabric API**
- **Fabric Language Kotlin**
- **Simple Voice Chat** on your client
- **Plasmo Voice** on the server you join

## Building from source

```sh
./gradlew build
```

The built jar is placed in `build/libs/`.

## License

-This project is licensed under [Apache License 2.0](LICENSE).
