# ADR 0033: Explicit remote MCP and web tools

- Status: Accepted and locally verified as E4 on 2026-11-02
- Scope: Public HTTPS MCP and Brave Web Search, independent of input/device access

## Decision

The accepted expansion adds `app/ai/AiRemoteHttpsTransport` as the only new
network adapter. It accepts typed MCP requests or the fixed Brave Web Search
endpoint, never arbitrary headers, redirects, cookies, proxies or retries.
Explicit discovery sends protocol metadata; a reviewed invocation sends its
displayed arguments. No automatic/background discovery occurs.

Connections and separate bearer/search credentials are saved only explicitly,
in `ai-connections.bin` with the independent AI_CONNECTIONS Keystore alias.
Existing formats are unchanged. AI clearing revokes and deletes this store/key.
Model credentials are never reused. Catalogs and sessions are memory-only.

Public DNS hostnames and HTTPS are required. The actual socket DNS resolver
rejects every non-public result, including mixed answers. URL prevalidation
alone is insufficient against rebinding. OkHttp 4.12.1 supplies socket-bound DNS
and standard platform TLS verification; its Okio 3.6.0 dependency is also pinned.
Both use Apache-2.0, install no components and perform no automatic network work.
Redirects, retries, proxies, cookies and persistent connection reuse are disabled.

MCP is a bounded 2025-11-25 Streamable HTTP subset: initialize/initialized,
tools/list/call, resources/list/read and prompts/list/get, with JSON/bounded SSE.
No stdio, OAuth, sampling, elicitation, roots, subscriptions, reconnect or code
installation. Resource/prompt results are quoted tool context, never system
instructions. Unsupported schemas are unavailable. Remote annotations cannot
grant read-only authority. Every operation requires approval; uncertain dispatch
stops without retry. Descriptor changes invalidate reviewed execution.

Brave uses only `https://api.search.brave.com/res/v1/web/search`, with a separate
user token. Each reviewed query returns at most five bounded title/URL/snippet
citations. No full-page fetch or embedded browser. Source opening requires a tap.

## Validation and limits

Cover private/mixed DNS, unsafe endpoints, isolated credentials, stale ownership,
oversized/malformed JSON/SSE, unsupported versions/server requests, schema changes,
hostile returned instructions, cancellation, uncertain writes, encrypted deletion
and private UI. Live interoperability requires explicit service credentials.

- https://modelcontextprotocol.io/specification/2025-11-25/basic/transports
- https://api-dashboard.search.brave.com/app/documentation/web-search/get-started
