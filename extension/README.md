# DSA Solution Capture

A minimal Chrome/Edge Manifest V3 extension that captures source code after an accepted submission on supported problem pages and sends it to a configured DSA Evaluator API.

## Load unpacked

1. Open `chrome://extensions` in Chrome or `edge://extensions` in Edge.
2. Enable **Developer mode**.
3. Choose **Load unpacked** and select this `extension/` directory.
4. Open the extension popup, enter the API base URL and extension token, then save.

The default API base URL is `http://localhost:8080/api`. Captures are posted to `{apiBaseUrl}/github/captures` with the token in `X-DSA-Extension-Token`.

## Supported pages

- LeetCode problem pages
- Codeforces problemset and contest problem pages
- GeeksforGeeks practice problem pages

Clicking a submit-like control arms a two-minute DOM observer. If an accepted-success phrase appears, the extension extracts source best-effort from a textarea, Monaco, CodeMirror, or Ace, then sends one capture per platform/problem/source hash.

## Security and limitations

- The token and API URL are stored in `chrome.storage.local`; they are not encrypted and are accessible to the local browser profile and this extension.
- Host access is limited to the supported coding sites, the production DSA Evaluator API, and the localhost development API.
- Requests explicitly omit credentials. The extension does not read or send cookies, session tokens, or platform credentials.
- Source extraction and accepted-state detection depend on third-party page markup and editor internals, which may change. Extraction can fail or select the wrong editor when multiple editors exist.
- The MAIN-world bridge exposes only a best-effort editor-source request/response over `window.postMessage`; page scripts can observe or interfere with that bridge.
- Use HTTPS for deployed APIs. HTTP is included only for the localhost development default.
- Successful capture hashes are retained locally for deduplication; clearing extension storage allows them to be sent again.
