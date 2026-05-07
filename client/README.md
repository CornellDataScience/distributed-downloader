# Client

CLI downloader. Fetches a file's manifest from the tracker, queries each live peer for its availability bitmap, assigns chunks to peers (least-assigned-so-far), and pulls them over one or more streaming `GetChunks` RPCs per peer. Output is written to `client/<filename>`.

## Run

```bash
make c FILE=Test1mb.bin
make c TRACKER_HOST=<ip> TRACKER_PORT=50051 FILE=file.bin
```

Or directly:

```bash
mvn -f client/pom.xml -DskipTests compile exec:java \
  -Dexec.mainClass=cds.distdownloader.client.Client \
  -Dexec.args="127.0.0.1 50051 env/manifest.json Test1mb.bin"
```

## CLI args

```
[trackerHost] [trackerPort] [manifestPath] [filename]
[maxAvailabilityParallelism] [maxDownloadParallelism] [quiet]
```

- Defaults: `127.0.0.1 50051 env/manifest.json`
- `manifestPath` is accepted but the manifest is fetched from the tracker by `filename`
- `maxDownloadParallelism` is accepted for backward compatibility; download concurrency is now `STREAMS_PER_PEER` streams per peer (set in `ClientService`)
- `0` for either parallelism arg means use the built-in default
- `quiet=true` suppresses verbose per-chunk logging

System properties (overridden by CLI args when both present):
`cds.distdownloader.maxAvailabilityParallelism`,
`cds.distdownloader.maxDownloadParallelism`,
`cds.distdownloader.quiet`.

## Files

- `Client.java` — entrypoint, arg parsing
- `ClientService.java` — tracker/peer RPCs, chunk assignment, parallel streaming download, direct positional writes to the output `FileChannel`
- `ClientConcurrencyConfig.java` — record for the two parallelism knobs
