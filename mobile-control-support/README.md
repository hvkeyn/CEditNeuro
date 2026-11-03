# Mobile control support

The `mobile-control` skill adds a phone-sized computer-use loop:

```text
ui_snapshot  →  choose a ref  →  ui_act  →  new snapshot  →  verify
```

It uses Android's accessibility tree, not screenshot coordinates. Requires Shizuku or root.

- `ui_snapshot` returns `@s<snapshot>:e<n>` refs, role, label, bounds, center, and actions.
- `ui_act` supports tap, long_click, set_text, clear_text, scroll_forward, scroll_backward, check, uncheck, launch, back, home, recents, wait.
- Refs are snapshot-scoped; a stale ref is refused on purpose.
- Passwords are redacted from snapshots.
- Destructive or external actions need the user's explicit approval.
- Fallback only: `fetch_system_layout` rows with `tap=X,Y` + `execute_system_action`.

Original adaptation for CEdiNeuro. Inspired by Vercel Labs Agent Desktop (Apache-2.0); no desktop/Rust code copied.

See `SKILLS.md` and `CEditNeuro/skills/mobile-control.md` on the phone.
