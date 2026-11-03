# Unbrowse playbook

## A. Discover → run → verify

1. `npx unbrowse discover "<task>"`.
2. Выбери `warm`/healthy capability; проверь inputs.
3. Запусти подходящий tool или `npx unbrowse run "<task>" --set k=v`.
4. Для мутации используй стабильный idempotency key.
5. Успех — только `succeeded` + verified result. HTTP 200 — не успех.
6. В отчёте: host, capability/tool, inputs, verified result, latency/credits, ограничения.

## B. Input required

```bash
npx unbrowse resume <runId> field=value
```

Продолжай **тот же** run. Не создавай второй.

## C. Нет capability

- Статическая страница/документ: `npx unbrowse scrape <url>` — последний вариант.
- Обучение сайта: `npx unbrowse index <url>` только с разрешения.
- `202` — это job id, а не tool. Проверяй `index status`; сообщи `jobId`, `indexed`, `stoppedReason`.
- Ошибка `model_unavailable` — сообщи и остановись, не повторяй цикл.

## D. Cloud browser (если доступен)

1. Открой страницу с задачей.
2. Называй поля (`query`, `date`, `origin`), чтобы параметры распознались.
3. Выполни задачу, заверши сессию, проверь `learnError`/`newTool`.
4. Для параметров полезны две сессии с разными входами.
5. Закрой сессии; не загружай приватные HAR/cookies.

## E. Exit codes

| Код | Значение | Действие |
|---|---|---|
| 0 | verified | отчитаться |
| 1 | error | показать ошибку |
| 2 | input required | resume |
| 3 | sign-in/site login | дать ссылку, ждать |
| 4 | not verified / unknown | проверить receipt, не повторять слепо |

## F. Тексты для отчёта

```text
Host:
Interface: CLI <version>
Auth: whoami exit 0 / OAuth link / not signed in
Capability:
Inputs:
Verification: succeeded/verified or unverified
Result:
Limit or next step:
```
