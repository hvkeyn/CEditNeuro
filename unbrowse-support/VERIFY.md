# Unbrowse verification checklist

- [ ] `npx unbrowse whoami` exit 0 (или явно показан OAuth/site-login шаг).
- [ ] Для задачи найден capability/tool; ID не выдуман.
- [ ] Inputs соответствуют схеме; нет лишних полей.
- [ ] Run завершён `succeeded`; `verified` не false.
- [ ] `input_required` продолжен через `resume` того же run.
- [ ] `outcome_unknown` не повторялся слепо; проверен receipt.
- [ ] `no_capability` → scrape/index/learn, а не выдуманный URL.
- [ ] Index job: показан `jobId`, `indexed`, `stoppedReason`.
- [ ] Нет ключей/токенов/cookies в отчёте, логах, файлах.
- [ ] Для записи было явное разрешение; idempotency key стабилен.
- [ ] В отчёте есть host, tool, inputs, verified result, ограничение.
- [ ] Если проверка не удалась — сказано «unverified», без ложного успеха.
