# Distributed Downloader

A LAN-first peer-to-peer downloader written in Java, Spring Boot, gRPC, and Protocol Buffers.

The project has three moving parts:

- `tracker/`: registry service that tracks live peers and the files they advertise
- `peer/`: gRPC file server that advertises one local file and serves its chunks
- `client/`: CLI downloader that asks the tracker for metadata and downloads chunks from peers
- `proto/`: shared protobuf contracts and generated Java gRPC classes

## Requirements

- Java 21 or newer
- Maven 3.9+

The current machine is using Java 25 successfully, but the project is configured around Java 21 source compatibility.

## Build

From the repo root:

```bash
# Build tracker, peer, and proto
make all

# Regenerate and install the local proto jar
make pr

# Full Maven build/install
mvn -DskipTests clean install
```

`tracker` and `peer` depend on the locally installed `cds.distdownloader:proto` snapshot. The Makefile installs `proto` before starting services so stale generated gRPC classes do not get loaded from `~/.m2`.

## Quick Start

Use separate terminals for tracker, peer, and client.

Terminal 1: start the tracker.

```bash
make t
```

Terminal 2: start a peer that shares a file.

```bash
make peer PEER_PORT=7003 SHARE_FILE=peer/Test1mb.bin
```

Terminal 3: download that file through the client.

```bash
make c FILE=Test1mb.bin
```

The output file is written to:

```text
client/Test1mb.bin
```

## Common Commands

```bash
# Start tracker on the default port, 50051
make t

# Start tracker on another port
make t TRACKER_PORT=50052

# Start a peer on the default peer port, 6001
make peer SHARE_FILE=peer/Test1mb.bin

# Start a peer on a specific port
make peer PEER_PORT=7003 SHARE_FILE=peer/Test1mb.bin

# Backward-compatible alias for peer port
make peer PORT=7003 SHARE_FILE=peer/Test1mb.bin

# Start a peer that connects to a non-default tracker
make peer PEER_PORT=7004 TRACKER_HOST=127.0.0.1 TRACKER_PORT=50051 SHARE_FILE=peer/Test100mb.bin

# Start a peer on another LAN machine
make peer PEER_PORT=7003 \
  TRACKER_HOST=<tracker-ip> \
  TRACKER_PORT=50051 \
  ADVERTISE_ADDRESS=<this-peer-lan-ip> \
  SHARE_FILE=/absolute/path/to/file.bin

# Download a file advertised by peers
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

## How Peers Share

Peers do not copy files to each other proactively. Sharing is pull-based:

1. A peer starts with `SHARE_FILE=/path/to/file`.
2. The peer reads that file, splits it into 1 MiB chunks, and stores those chunks in memory.
3. Every 5 seconds, the peer sends a heartbeat to the tracker.
4. The heartbeat includes the peer endpoint plus a manifest entry for the shared file.
5. The tracker records which peers are alive and which filenames they advertise.
6. A client asks the tracker for the file manifest, then asks the tracker for live peers.
7. The client asks each peer for an availability bitmap.
8. The client assigns chunks to peers (least-assigned-so-far) and opens one streaming `GetChunks` RPC per peer. All chunks assigned to a peer arrive over a single HTTP/2 stream rather than one RPC per chunk.
9. The client assembles the chunks into `client/<filename>`.

So the tracker is only a directory. File bytes move directly from peers to the client over the peer gRPC service.

Currently, each peer seeds the entire file named by `SHARE_FILE`. If several peers share the same filename, the client can query all of them and distribute chunk requests across the available owners. The old randomized partial-chunk demo code is still present but commented out.

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
  SHARE_FILE=/absolute/path/to/shared-file.bin
```

On the client machine:

```bash
make c TRACKER_HOST=<tracker-ip> TRACKER_PORT=50051 FILE=shared-file.bin
```

The filename passed to `FILE` must match the basename of the peer's `SHARE_FILE`. For example, `SHARE_FILE=/tmp/game.zip` is requested with `FILE=game.zip`.

## Configuration

Tracker:

- `spring.grpc.server.port`: tracker gRPC port, default `50051`

Peer:

- `peer.port`: peer gRPC server port, default `6001`
- `tracker.address`: tracker host, default `localhost`
- `tracker.port`: tracker port, default `50051`
- `peer.advertise-address`: address that clients should use to reach this peer, default `127.0.0.1`
- `peer.share-file`: file this peer advertises and serves

Client arguments:

```text
[trackerHost] [trackerPort] [manifestPath] [filename] [maxAvailabilityParallelism] [maxDownloadParallelism]
```

Note: `manifestPath` is still accepted by the client CLI, but the current download path gets the manifest from the tracker using `filename`.

`maxDownloadParallelism` is accepted for backward compatibility but no longer used. Download concurrency is now one streaming RPC per peer — the client assigns chunks to peers up front and opens one `GetChunks` stream per peer in parallel rather than one RPC per chunk.

## Troubleshooting

If you see `NoSuchMethodError` for `getFilesList` or `addAllFiles`, restart every running tracker and peer JVM after `make pr`. Old Spring Boot processes keep old classes loaded until they exit.

```bash
make pr
# Ctrl-C old tracker and peer terminals
make t
make peer PEER_PORT=7003 SHARE_FILE=peer/Test1mb.bin
```

If a peer starts but the client cannot download anything, check that the peer was started with `SHARE_FILE` and that the client `FILE` value is the same filename.

If you run across multiple machines, set `ADVERTISE_ADDRESS` to the peer's LAN IP. Leaving it as `127.0.0.1` makes remote clients try to connect to themselves.

## Current Limitations

- Tracker state is in memory only.
- Peers load the shared file chunks into memory.
- Hash verification is not enforced by the client yet.
- Origin fallback is not implemented.
- Client output always goes to `client/<filename>`.
- The tracker returns all live peers; the client filters by asking each peer for availability.

## Team

- Naijei Jiang*
- Harshaan Chugh*
- Tanvi Bhave
- Sabrina Ning
- Skai Nzeuton
- Rahi Dasgupta
- Yitbrek Mata
