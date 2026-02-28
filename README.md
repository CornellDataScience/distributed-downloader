# Distributed Downloader

LAN-first peer-to-peer distributed downloader for game/software installs and updates.

When multiple machines on the same LAN need the same file, peers can share chunks locally to reduce origin bandwidth and improve install speed.

## Current Architecture (Plan A)

1. **Tracker** (`tracker/`, Spring Boot + gRPC)
- Tracks peer liveness only (`peer_id`, `ip`, `port`, `last_seen`)
- RPCs: `Register`, `Heartbeat`, `ListPeers`
- No tracker-side chunk availability yet

2. **Peer** (`peer/`, Spring Boot + gRPC)
- Serves chunk availability and chunk bytes
- RPCs: `GetAvailability(file_id)`, `GetChunk(file_id, chunk_index)`

3. **Client** (planned/next)
- Loads manifest
- Asks tracker for live peers
- Fetches availability bitmap from peers
- Downloads chunks in parallel and verifies SHA-256
- Falls back to origin when needed

## Repo Structure

- `proto/` - Protobuf definitions + generated gRPC Java classes
- `tracker/` - Tracker service
- `peer/` - Peer service
- `env/` - local sample files/notes

## Protobuf Notes

- Protobuf package: `cds.distdownloader.v1`
- Generated Java package: `cds.distdownloader.proto`
- Generated classes and stubs come from:
    - `common.proto`
    - `tracker.proto`
    - `peer.proto`

## Prerequisites

- Java 21
- Maven 3.9+

## Build

From repo root:

```bash
mvn clean install
```

Useful module-specific builds:

```bash
# Regenerate + install proto artifact
mvn -pl proto -am clean install -DskipTests

# Compile peer with reactor dependencies
mvn -pl peer -am clean compile

# Compile tracker with reactor dependencies
mvn -pl tracker -am clean compile
```

## Run Services

From repo root:

```bash
# Run tracker
mvn -pl tracker spring-boot:run

# Run peer
mvn -pl peer spring-boot:run
```

## Generated gRPC Output

After building `proto`:

- Java protobuf classes: `proto/target/generated-sources/protobuf/java`
- gRPC stubs: `proto/target/generated-sources/protobuf/grpc-java`

## Current MVP Status

- Proto contracts are defined for Tracker and Peer services
- Spring gRPC scaffolding is present in tracker and peer modules
- Tracker/Peer business logic is still being implemented (MVP phase)

## Team

- Naijei Jiang
- Harshaan Chugh
- Tanvi Bhave
- Sabrina Ning
- Skai Nzeuton
- Rahi Dasgupta