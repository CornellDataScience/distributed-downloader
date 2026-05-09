
# Distributed Downloader

A LAN-first peer-to-peer file downloader built for Cornell's CS 4410 / CDS course project. Written in Java with Spring Boot, gRPC, and Protocol Buffers.

**[Final Presentation Slides](CDS%20Distributed%20Downloader.pdf)**

## Architecture

The project has four components:

- `tracker/`: registry service that tracks live peers and the files they advertise
- `peer/`: gRPC file server that advertises one local file and serves its chunks
- `client/`: CLI downloader that queries the tracker for metadata and pulls chunks from peers in parallel
- `proto/`: shared Protobuf contracts and generated Java gRPC stubs

File bytes never flow through the tracker. The tracker is a directory only — chunks move directly from peers to the client over peer gRPC streams.

## How It Works

1. A peer starts up with `SHARE_FILE=/path/to/file`, splits the file into 1 MiB chunks, and holds them in memory.
2. Every 5 seconds the peer sends a heartbeat to the tracker advertising its endpoint and filename.
3. The tracker records which peers are alive and what they serve.
4. A client asks the tracker for the file manifest and a list of live peers.
5. The client queries each peer for a chunk availability bitmap.
6. The client assigns chunks across peers (least-assigned-first) and opens one streaming `GetChunks` RPC per peer. All chunks for a given peer arrive over a single HTTP/2 stream.
7. The client reassembles chunks and writes the output to `client/<filename>`.

Multiple peers can share the same filename. The client distributes chunk requests across all available owners.

## Requirements

- Java 21 or newer (tested on Java 25)
- Maven 3.9+

## Build

```bash
# Build tracker, peer, and proto
make all

# Regenerate and install the local proto jar
make pr

# Full Maven build/install
mvn -DskipTests clean install
```

`tracker` and `peer` depend on the locally installed `cds.distdownloader:proto` snapshot. The Makefile installs `proto` before starting services to prevent stale gRPC classes from being loaded out of `~/.m2`.

## Quick Start

Open three terminals.

**Terminal 1 — tracker:**

```bash
make t
```

**Terminal 2 — peer:**

```bash
make peer PEER_PORT=7003 SHARE_FILE=peer/Test1mb.bin
```

**Terminal 3 — client:**

```bash
make c FILE=Test1mb.bin
```

The downloaded file is written to `client/Test1mb.bin`.

## Common Commands

```bash
# Start tracker on the default port (50051)
make t

# Start tracker on a custom port
make t TRACKER_PORT=50052

# Start a peer on the default peer port (6001)
make peer SHARE_FILE=peer/Test1mb.bin

# Start a peer on a specific port
make peer PEER_PORT=7003 SHARE_FILE=peer/Test1mb.bin

# Backward-compatible alias for peer port
make peer PORT=7003 SHARE_FILE=peer/Test1mb.bin

# Start a peer pointing at a non-default tracker
make peer PEER_PORT=7004 TRACKER_HOST=127.0.0.1 TRACKER_PORT=50051 SHARE_FILE=peer/Test100mb.bin

# Start a peer on a remote LAN machine
make peer PEER_PORT=7003 \
  TRACKER_HOST=<tracker-ip> \
  TRACKER_PORT=50051 \
  ADVERTISE_ADDRESS=<this-peer-lan-ip> \
  SHARE_FILE=/absolute/path/to/file.bin

# Download a file from peers
make c FILE=Test1mb.bin

# Download through a tracker on another host
make c TRACKER_HOST=<tracker-ip> TRACKER_PORT=50051 FILE=file.bin
```

Equivalent Maven commands:

```bash
# Tracker
mvn -pl proto -am -DskipTests install
mvn -pl tracker spring-boot:run \
  -Dspring-boot.run.arguments="--spring.grpc.server.port=50051"

# Peer
mvn -pl proto -am -DskipTests install
mvn -pl peer spring-boot:run \
  -Dspring-boot.run.arguments="--peer.port=7003 --tracker.address=127.0.0.1 --tracker.port=50051 --peer.advertise-address=127.0.0.1 --peer.share-file=peer/Test1mb.bin"

# Client
mvn -f client/pom.xml -DskipTests compile exec:java \
  -Dexec.mainClass=cds.distdownloader.client.Client \
  -Dexec.args="127.0.0.1 50051 env/manifest.json Test1mb.bin"
```

## Hosted Tracker

A tracker is running on Cornell's eduroam network. Connect to eduroam, then use this IP for any `TRACKER_HOST` argument:

```
10.49.14.104
```

Example — download a file from the hosted tracker:

```bash
make c TRACKER_HOST=10.49.14.104 TRACKER_PORT=50051 FILE=yourfile.bin
```

Example — register a peer with the hosted tracker:

```bash
make peer PEER_PORT=7003 TRACKER_HOST=10.49.14.104 TRACKER_PORT=50051 ADVERTISE_ADDRESS=<your-eduroam-ip> SHARE_FILE=/path/to/file.bin
```

## LAN Setup

On the tracker machine:

```bash
ipconfig getifaddr en0
make t TRACKER_PORT=50051
```

On each peer machine:

```bash
ipconfig getifaddr en0
make peer \
  PEER_PORT=7003 \
  TRACKER_HOST=<tracker-ip> \
  TRACKER_PORT=50051 \
  ADVERTISE_ADDRESS=<peer-ip> \
  SHARE_FILE=/absolute/path/to/file.bin
```

On the client machine:

```bash
make c TRACKER_HOST=<tracker-ip> TRACKER_PORT=50051 FILE=file.bin
```

The `FILE` value must match the basename of the peer's `SHARE_FILE`. For example, `SHARE_FILE=/tmp/game.zip` is requested with `FILE=game.zip`.

## Configuration

**Tracker:**

| Property | Default | Description |
|---|---|---|
| `spring.grpc.server.port` | `50051` | Tracker gRPC port |

**Peer:**

| Property | Default | Description |
|---|---|---|
| `peer.port` | `6001` | Peer gRPC server port |
| `tracker.address` | `localhost` | Tracker host |
| `tracker.port` | `50051` | Tracker port |
| `peer.advertise-address` | `127.0.0.1` | Address clients use to reach this peer |
| `peer.share-file` | — | File this peer advertises and serves |

**Client arguments:**

```text
[trackerHost] [trackerPort] [manifestPath] [filename] [maxAvailabilityParallelism] [maxDownloadParallelism]
```

`manifestPath` is accepted but ignored — the manifest is fetched live from the tracker using `filename`. `maxDownloadParallelism` is accepted for backward compatibility but no longer used; concurrency is now one streaming RPC per peer.

## Troubleshooting

**`NoSuchMethodError` for `getFilesList` or `addAllFiles`:** Restart all tracker and peer JVMs after `make pr`. Old processes keep stale classes loaded until they exit.

```bash
make pr
# Ctrl-C all tracker and peer terminals, then:
make t
make peer PEER_PORT=7003 SHARE_FILE=peer/Test1mb.bin
```

**Client connects but downloads nothing:** Confirm the peer was started with `SHARE_FILE` and that the `FILE` argument matches the basename exactly.

**Remote peers unreachable:** Set `ADVERTISE_ADDRESS` to the peer's LAN IP. Leaving it as `127.0.0.1` causes remote clients to try connecting to themselves.

## Current Limitations

- Tracker state is held in memory and is lost on restart.
- Peers load the entire shared file into memory at startup.
- Chunk hash verification is not enforced client-side.
- No origin fallback if all peers serving a chunk go offline mid-download.
- Client output is always written to `client/<filename>`.

## Team

| Name | Role |
|---|---|
| Naijei Jiang | Tech Lead |
| Harshaan Chugh | Project Lead |
| Tanvi Bhave | Engineer |
| Sabrina Ning | Engineer |
| Skai Nzeuton | Engineer |
| Rahi Dasgupta | Engineer |
| Yitbrek Mata | Engineer |
