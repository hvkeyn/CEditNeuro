# Unbrowse support pack

Этот пакет — тексты и чек-листы к встроенному скиллу `unbrowse` в CEdiNeuro. Он не содержит ключей, токенов, cookies или чужих данных.

- [SETUP.md](SETUP.md) — установка CLI, OAuth, device flow, API key через окружение.
- [PLAYBOOK.md](PLAYBOOK.md) — discover → run → verify; scrape/index/learn; resume.
- [PROMPTS.md](PROMPTS.md) — готовые фразы для CEdiNeuro.
- [SECURITY.md](SECURITY.md) — авторизация, ToS, CAPTCHA/MFA, приватность.
- [VERIFY.md](VERIFY.md) — что считать доказанным результатом.
- [SKILL.md](SKILL.md) — точный текст скилла, установленного на телефон.
- [ATTRIBUTION.md](ATTRIBUTION.md) — MIT и границы адаптации.

Быстрый старт:

```text
Use skill unbrowse.
Check `npx unbrowse whoami`.
If exit 3, sign in with OAuth; do not ask me for a key.
Then discover and run the task; report only a verified result.
```

Unbrowse — hosted-сервис `https://unbrowse.ai`; клиент MIT: https://github.com/unbrowse-ai/unbrowse. CEdiNeuro не получает аккаунт Unbrowse автоматически и не хранит его ключ.
