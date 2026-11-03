# Unbrowse setup for CEdiNeuro

## 1. Проверить, что уже есть

```bash
npx unbrowse whoami
```

- exit `0` — вход выполнен;
- exit `3` — нужен вход;
- `npx`/Node нет — установка runtime только с разрешения пользователя.

Не устанавливай Node, npm-пакеты или CLI молча.

## 2. OAuth в браузере (обычный путь)

```bash
npx unbrowse login --no-open
```

Команда печатает ссылку `https://unbrowse.ai/authorize?...`. Пользователь открывает её **на том же устройстве**, где запущен CLI, и нажимает Allow. Дождись строки `Signed in`, затем повтори `whoami`.

Не проси пароль в чате. Не вставляй ссылку как «токен».

## 3. Headless / удалённая сессия

```bash
npx unbrowse login --device
```

Покажи код пользователю; он подтверждает его на любом устройстве. Дождись успеха, не запускай команду повторно в цикле.

## 4. API key для автоматизации

Только если пользователь сам положил ключ в своё секретное окружение:

```bash
export UNBROWSE_API_KEY=...   # выполняет пользователь, не агент
```

- не печатай значение;
- не пиши его в файл, prompt, URL, аргумент команды или лог;
- не проси прислать ключ в чат.

## 5. MCP (вне CEdiNeuro)

У CEdiNeuro нет встроенного MCP-клиента. Если пользователь настраивает MCP в другом клиенте, используй документированный URL `https://unbrowse.ai/mcp` и OAuth этого клиента. Не добавляй bearer-header в конфиг: с ним хост может пропустить OAuth. В CEdiNeuro работай через CLI или публичный OpenAPI.

## 6. Проверка после входа

```bash
npx unbrowse whoami
npx unbrowse discover "top stories on hacker news"
```

Покажи, что вернул discover: capability, состояние (`warm`/`rendered`), health. Не выдумывай ID.
