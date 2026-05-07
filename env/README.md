# env

Shared config and the local file manifest used by the client when no tracker manifest is available.

- `manifest.json` — file entries (`filename`, `filesize`, `chunkSize`, `origin`, `hashAlgorithm`). Sizes are in bytes; 1 MiB = 1024 × 1024.
- `hardcoded.java` — default tracker host/port constants (`127.0.0.1:50051`).
