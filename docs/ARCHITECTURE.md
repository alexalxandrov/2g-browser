# Architecture notes

- `MainActivity`: neutral toolbar, URL/search field, progress and resource statistics, privacy controls.
- `BrowserClient`: top-level navigation, SSL/Safe Browsing decisions, and WebChromeClient bridge.
- `ResourceInterceptor`: GET interception through OkHttp, validator revalidation, conditional Range resume, cache response streaming, counting bytes.
- `SinglePageCache`: per-URL body + JSON metadata in private app storage, keyed by SHA-256; deletes prior-page entries on navigation and evicts old entries above 128 MiB.
- `TrackerBlocker`: local denylist; no list download or tracking request is generated.
- `TransferStats`: separates WebView progress from bytes observed by the interception layer.

The current design is an experiment, not a hardened browser engine. A production implementation should have instrumented integration tests, a persistent telemetry model keyed by navigation generation, formal HTTP cache semantics (`Vary`, freshness, validators, credentials), a more complete public-suffix-aware cookie policy, robust redirect handling and fuzzing of `Content-Range` handling before broad distribution.
