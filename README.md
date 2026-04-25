# Distributed Downloader

LAN-first peer-to-peer distributed downloader for game/software installs and updates.

When multiple machines on the same LAN need the same file, peers can share chunks locally to reduce origin bandwidth and improve install speed.

## Current Architecture (Plan A)

1. **Tracker** (`tracker/`, Spring Boot + gRPC)
- Tracks peer liveness (`peer_id`, `ip`, `port`, `last_seen`)
- Tracks which file IDs each peer advertises in memory
- RPCs: `Heartbeat`, `ListPeers`

2. **Peer** (`peer/`, Spring Boot + gRPC)
- Serves chunk availability and chunk bytes
- RPCs: `GetAvailability(file_id)`, `GetChunk(file_id, chunk_index)`
- Current implementation is demo-oriented around `Test.bin`

3. **Client** (`client/`, standalone Maven module)
- Loads manifest
- Asks tracker for live peers
- Fetches availability bitmap from peers
- Downloads chunks in parallel
- Assembles the downloaded chunks into an output file
- SHA-256 verification and origin fallback are still planned

## Repo Structure

- `proto/` - Protobuf definitions + generated gRPC Java classes
- `tracker/` - Tracker service
- `peer/` - Peer service
- `client/` - CLI-style client used to download and assemble chunks
- `env/` - local sample files/notes

## Protobuf Notes

- Protobuf package: `cds.distdownloader.v1`
- Generated Java package: `cds.distdownloader.proto`
- Generated classes and stubs come from:
    - `common.proto`
    - `tracker.proto`
    - `peer.proto`
    - `client.proto`

## Prerequisites

- Java 21
- Maven 3.9+

## Build

From repo root, build the reactor modules (`proto`, `tracker`, `peer`):

```bash
mvn -DskipTests clean install
```

The client is currently built as a separate Maven project:

```bash
mvn -f client/pom.xml -DskipTests compile
```

Useful module-specific builds:

```bash
# Regenerate + install proto artifact
mvn -pl proto -am clean install -DskipTests

# Compile peer with reactor dependencies
mvn -pl peer -am clean compile

# Compile tracker with reactor dependencies
mvn -pl tracker -am clean compile

# Compile client
mvn -f client/pom.xml -DskipTests compile
```

## Run Services

From repo root:

```bash
# Run tracker
mvn -pl tracker spring-boot:run

# Run peer
mvn -pl peer spring-boot:run
```

Also at repo root, leverage MakeFile:

```bash
# Run tracker
make t

# Run peer
make peer PORT=7003

# Run client
make c

# Recompile proto files
make pr
```

Run with different IP addresses

Laptop A (tracker):
```bash
# Find IP address
ipconfig getifaddr en0

# Start tracker
mvn -pl tracker spring-boot:run -Dspring-boot.run.arguments="--spring.grpc.server.port=<trackerPort>"
```

Laptop B (peer):
```bash
# Verify connectivity
grpcurl -plaintext <trackerAddress>:<trackerPort> list

# Point peer to tracker IP
mvn -pl peer spring-boot:run -Dspring-boot.run.arguments="--spring.grpc.server.port=<peerPort> --tracker.address=<trackerAddress> --tracker.port=<trackerPort>"
```

## Generated gRPC Output

After building `proto`:

- Java protobuf classes: `proto/target/generated-sources/protobuf/java`
- gRPC stubs: `proto/target/generated-sources/protobuf/grpc-java`

## Current MVP Status

- Proto contracts are defined for Tracker and Peer services
- Tracker stores live peer endpoints and advertised file IDs in memory
- Peer serves demo chunk availability and chunk bytes for `Test.bin`
- Client can query peers, download advertised chunks in parallel, and assemble the file
- Hash verification, origin fallback, persistent storage, and general file indexing are not implemented yet

## Team

- Naijei Jiang*
- Harshaan Chugh*
- Tanvi Bhave
- Sabrina Ning
- Skai Nzeuton
- Rahi Dasgupta
- Yitbrek Mata
