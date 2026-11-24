# Archify support

Upstream: https://github.com/tt-a1i/archify · MIT.

Archify upstream is an agent skill plus a **Node** CLI: it validates a typed JSON IR and emits a self-contained HTML/SVG artifact. This app does **not** install Node or the upstream validator. The local `archify` tool keeps the typed-IR direction and renders the phone-sized interactive page itself.

## Five modes

| Type | Use |
|---|---|
| `architecture` | services, stores, boundaries |
| `workflow` | steps, gates, a process |
| `sequence` | actors and ordered messages |
| `dataflow` | sources, transforms, sinks |
| `lifecycle` | states and transitions |

## IR

```json
{
  "type": "architecture",
  "title": "Upload pipeline",
  "subtitle": "File to review",
  "nodes": [
    {"id":"ui","label":"Mobile UI","kind":"frontend","group":"client","detail":"Picks a file."},
    {"id":"api","label":"Upload API","kind":"backend","group":"core","detail":"Checks a type."},
    {"id":"store","label":"Object store","kind":"database","group":"core","detail":"Stores bytes."}
  ],
  "edges": [
    {"from":"ui","to":"api","label":"PUT"},
    {"from":"api","to":"store","label":"write"}
  ],
  "groups": [
    {"id":"client","label":"Client"},
    {"id":"core","label":"Server"}
  ]
}
```

## Limits

- 16 nodes / 32 edges; 12 main nodes recommended.
- The page is a description, not runtime tracing, deployment ownership, or a security proof.
- No uploaded secrets, no source footage, no external assets.
- The upstream Node CLI's 9-point showcase check, PNG/WebM export, and delta comparison are not part of this phone tool.
