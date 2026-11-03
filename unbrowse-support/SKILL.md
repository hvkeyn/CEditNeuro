# Unbrowse: call the site's own API

Use this when the user asks to "unbrowse" a site, reverse engineer a website or API, avoid browser automation, get structured data from a site, or make an agent talk to a site's first-party HTTP API. Unbrowse is a hosted compiler and verified replay service; its client is MIT (github.com/unbrowse-ai/unbrowse). CEdiNeuro has no built-in MCP client, so on this phone use the CLI through run_command, or a public OpenAPI document through http_request.

## Authorization and secrets

Only work on public pages, or sites the user owns or has written permission to test. Do not bypass a login, paywall, CAPTCHA, MFA, rate limit, or access control. Do not collect credentials, cookies, session tokens, or private HAR files. Posting, sending, purchasing, deleting, and other external writes need explicit approval for that action.

Never ask the user to paste an API key into chat. Never write a key into a file, prompt, command argument, URL, or log. The only accepted key path is the user's own secret environment variable `UNBROWSE_API_KEY`; if it is absent, use browser OAuth. An OAuth link is not a key. Do not print the value of any environment variable.

## Check the interface first

`run_command` is available on every turn. Run `npx unbrowse whoami` with a short timeout. Exit 0 means signed in; exit 3 means not signed in. If `npx` or Node is missing, say so and ask before installing a runtime; do not silently install Node, a package, or the CLI.

Do not invent a capability ID, tool schema, endpoint, or result. If the CLI is unavailable and the user has a public compiled-site OpenAPI URL, `http_request` the `https://unbrowse.ai/api/v1/sites/<host>/openapi.json` document and follow only operations it actually lists. Do not attach a bearer key unless the user already placed it in the environment and the app's HTTP tool can read it without exposing it.

## Sign in

Preferred flow, when a person can open a browser on this machine:

1. Start `npx unbrowse login --no-open` in the background with run_command.
2. Read the printed `https://unbrowse.ai/authorize?...` link and give it to the user.
3. Wait for the CLI's `Signed in` line. Do not claim success before it appears.
4. Re-check with `npx unbrowse whoami`.

For a headless or remote session use `npx unbrowse login --device`, show the code, and wait for approval. Never ask for a password in chat. Unbrowse account sign-in and a website's saved login are separate; a site login uses the hosted vault or a one-time link and is never typed into a tool argument.

## Discover and run

1. `npx unbrowse discover "<task>"`. Read every returned capability's state, health, inputs, hints, and `next`. Prefer `warm` (HTTP replay) over `rendered` (needs a browser). Skip `failing`, `excluded`, and `needs_sign_in` when another route fits.
2. Call the matching tool, or `npx unbrowse run "<task>" --set key=value`, using the listed schema. Use a stable idempotency key for the same mutation. Do not add fields the schema did not list.
3. Treat only a verified result as success. Check the status, the actual result, and the verification field. An HTTP 200, a screenshot, a generated plan, or a transport success is not success.
4. `input_required` means answer the open requirements on the same run: `npx unbrowse resume <runId> field=value`. Never start a second run for the same question.
5. `no_capability` means there is no reusable route. For a static page or document, `npx unbrowse scrape <url>` is the last resort. To learn the site, `npx unbrowse index <url>` or the cloud-browser flow, but only after authorization. `202` from index is a job ID, not a compiled tool; poll `index status` and report the job id, `indexed` count, and `stoppedReason`.
6. Report the tool or capability, the inputs, the verified result, the source URL, and latency or credits when shown. If verification is absent, say "unverified" and stop.

## Exit codes and recovery

`0` verified success; `1` error; `2` input required; `3` sign-in or site login needed; `4` not verified or outcome unknown.

- `3`: give the OAuth/site-login link, then wait. Do not retry in a loop.
- `2`: resume the same run with the requested fields.
- `4` / `outcome_unknown`: a write may have happened. Inspect the receipt and destination before any retry. Do not retry blindly.
- `401` / `403`: report the auth boundary; do not try to defeat it.
- `409 tool_quarantined`: use another capability; do not bypass the quarantine.
- `504 run_timeout`: keep the run id and poll `inspect`; do not start a duplicate.
- `model_unavailable` during indexing: report the job id and stop; do not loop.

## Learning a site

Prefer an existing compiled tool. When none exists and the user authorized the work:

1. Open the cloud browser, name every filter/input you exercise, and complete the task once.
2. Finish the session to compile observed routes. Check `learnError` and `newTool`; a completed browse is not a reusable API.
3. Use two sessions with different inputs when the parameters are unclear.
4. Close sessions. Do not upload private HARs, cookies, or credentials. Only recordings the user authorized may be submitted.
5. A site may forbid automated access in its terms. Say so and stop; do not treat a technical bypass as permission.

## CEdiNeuro tool map

- `run_command`: `npx unbrowse whoami`, `discover`, `run`, `resume`, `scrape`, `index`, `index status`.
- `http_request`: read a public OpenAPI document or a documented public endpoint; no secret in the URL.
- `write_file` + `read_file`: save a route report under `research/unbrowse/` with the task, host, tool, inputs, verification, and unresolved limits.
- `load_tools group=study`: research tools when the task also needs sources; Unbrowse itself is not a new tool or permission.
- `open_file`: show the report when it is finished.

## Output order

1. What was asked and which site/host.
2. Whether the interface was signed in; never print a secret.
3. The capability/tool and inputs.
4. Verified result, or the exact blocker and exit code.
5. One next step: sign in, resume, index, or ask for authorization.

Do not promise universal site coverage. Do not claim browserless execution on the first request if the route still needs indexing. Do not reuse one user's session or `X-Unbrowse-End-User` for another.
