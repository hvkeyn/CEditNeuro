# Готовые промпты для CEdiNeuro

## Разведка и вызов API

```text
Use skill unbrowse.
Unbrowse the public site example.com for <task>.
Check `npx unbrowse whoami` first. If it exits 3, give me the OAuth link; do not ask for a key.
Discover a capability, run it, and report only a verified result with the tool and inputs.
```

## Если сайт ещё не скомпилирован

```text
Use skill unbrowse.
There is no existing capability for <host>. This is my site / I have permission.
Index <url> with focus "<read-only task>", report the job id, indexed count, and stoppedReason.
Do not bypass a login, CAPTCHA, or paywall.
```

## Продолжить run, которому нужны поля

```text
Use skill unbrowse.
The run is in input_required. Resume the same run <runId> with <field>=<value>.
Do not start a second run.
```

## Осторожная мутация

```text
Use skill unbrowse.
This action writes/sends/purchases: <action>. Ask me for explicit approval before calling it.
Use the capability's schema and a stable idempotency key.
If the result is outcome_unknown, inspect the receipt first; do not retry blindly.
```

## Проверка перед отчётом

```text
Use skill unbrowse.
Before saying it worked, quote the verified result, the capability ID, the host, and the exit code.
If verification is missing, say "unverified" and stop.
```

## Запрещённые просьбы

```text
Do not bypass a login, CAPTCHA, MFA, paywall, rate limit, or access control.
Do not collect credentials, cookies, or private HAR files.
Do not ask me to paste an API key in chat.
```
