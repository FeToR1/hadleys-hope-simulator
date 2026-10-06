# Симулятор колонии

Учебный проект: Colony → компилятор ANTLR4/Kotlin → стековая ВМ C++ → ядро мира → React-дашборд.

**Новая цель — 5000 домов: Экосистема Лес/Море, Летающие крокодилы, ПВО и Креатиновая экономика.**
Посёлок масштабирован до 5000 домов (более 20 000 параллельных контекстов). Внедрено 2D пространственное хеширование (`SpatialIndex`, $O(1)$) для исключения $O(N^2)$ при коллизиях и проверках близости. Посёлок окружён лесом (З, С, Ю) и морем (В). Смоделированы: эрозия берега, волны морского тумана, необходимость респираторов, атаки летающих крокодилов (с шумом Перлина), системы ПВО периметра/крыш, добыча и продажа креатина, исцеление раненых и ремонтная очередь.

## Документация

- [docs/architecture.md](docs/architecture.md) — архитектура: подсистемы, диаграммы (конвейер, компоненты, conformance), структура кодовой базы, руководство по расширению.
- [docs/requirements.md](docs/requirements.md) — ТЗ «Клякса»: цели, требования, границы, риски, критерии приёмки, что не реализовано.
- [docs/onboarding.md](docs/onboarding.md) — маршрут изучения проекта для нового разработчика (один проход).
- Спецификации: [язык Colony и сценарии](docs/spec/colony-language.md) · [исполнение, тик, физика, PRNG](docs/spec/runtime-tick.md) · [формат .cvm и протокол ВМ](docs/spec/cvm-format.md) · [HTTP/SSE API](docs/spec/http-api.md).
- [docs/glossary.md](docs/glossary.md) — термины (Клякса, тик, контекст поведения, ровер, креатин и др.).
- [docs/adr/](docs/adr/README.md) — архитектурные решения и их обоснования («почему так устроено»).
- [CHANGELOG.md](CHANGELOG.md) — что и когда менялось; [docs/history/optimization-5000.md](docs/history/optimization-5000.md) — исторический отчёт об оптимизациях с замерами.
- [AGENTS.md](AGENTS.md) — точка входа для LLM-агентов: карта репозитория, команды, «что читать / что обновлять».
- Правила ведения документации: [docs/contribution/documentation-policy.md](docs/contribution/documentation-policy.md).

## Запуск

### Из VS Code (в один клик)

1. Откройте папку проекта в VS Code.
2. Нажмите **`Ctrl + Shift + B`** (или меню `Terminal` → `Run Build Task...`).
3. Выберите **`1. Запуск симулятора (5000 домов + Экосистема)`**.
4. Интерфейс откроется по адресу: <http://localhost:5173>, бэкенд — <http://localhost:8080>.

### Из терминала (PowerShell)

5000 домов с новой экосистемой на Windows, из корня проекта:

```powershell
.\scripts\start-5000.ps1 -Scenario examples/physics/settlement-5000.json
```

Скрипт собирает backend и запускает backend/UI в фоне на портах 8080/5173. Использует сценарий из
параметра `-Scenario` (в примере выше и по умолчанию — `settlement-5000.json`), 4 рабочих потока поведения,
компактный поток наблюдений, общий JVM-процесс с максимумом heap 2 ГиБ и целевую скорость 10 тиков/с.
Сценарий `full-5000.json` содержит те же настройки мира на 5000 домах — 20 028 контекстов; климат,
мощности, службы, шаг 1 с и длительность суток сохранены из `full.json`.
Логи и PID — `results/local-5000.json`, `results/*-5000*.log`. `-Workers N` меняет число рабочих потоков,
`-Speed 10` задаёт целевые 10 тиков/с, `-BackendPort`/`-FrontendPort` меняют порты, `-SkipBuild` использует готовую сборку.
На этой машине замер объединённого `development` с компактным кодированием даёт около 6,22 тика/с без HTTP и браузера:
заданная скорость не гарантирует фактическую. Быстрый расчёт без промежуточных снимков достигает 10,47 тика/с
в коротком замере без записи журнала; условия и ограничения приведены в [отчёте](docs/history/optimization-5000.md#проверка-и-слияние-с-development).
Это эталонный режим `reference`; текущий `serve-native` всё ещё создаёт процесс на сущность.
Карта объединяет дома и жителей в группы издалека, при приближении рисует только видимую область.

Для ручного запуска и замера после `installDist` (на Windows пересборка требует остановки сервера,
использующего JAR из этого каталога):

```powershell
$env:JAVA_OPTS = '-Xms512m -Xmx2g'
$env:HH_REFERENCE_WORKERS = '4'
$env:HH_COMPACT_OBSERVER = '1'
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat benchmark examples/physics/full-5000.json 120
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat benchmark-live examples/physics/full-5000.json 120
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat benchmark-fast examples/physics/full-5000.json 120
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat serve examples/physics/full-5000.json 8080
```

Без `HH_REFERENCE_WORKERS` эталонный исполнитель использует один рабочий поток. Ручной `serve` начинает
прогон при подключении наблюдателя или `POST /control/resume`. Искусственные потолки 10 000 сущностей и
1 000 000 тиков сняты; количества остаются положительными/неотрицательными `Int`, общий размер манифеста проверяется до переполнения.
`HH_COMPACT_OBSERVER=1` отключает обязательное кодирование полных кадров на каждом шаге сервера.
UI запрашивает `/stream?format=compact`; обычный `/stream` и JSONL сохраняют полный формат.

Для расчёта сценария без карты используйте `run-fast SCENARIO [OUTPUT.jsonl]`. Команда выполняет каждый тик
модели и строит полный снимок в конце. Без файла выводится один JSON с итогами и контрольной суммой;
с файлом сохраняются события и проводки каждого тика, а последний кадр содержит полный мир.
Промежуточные кадры имеют `full=false` и пустые списки сущностей и намерений: это журнал фактов,
его нельзя загружать как запись полных кадров наблюдателя. Обычный `run` сохраняет полный журнал.

```powershell
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat run-fast examples/physics/full-5000.json
```

Сценарий через Docker:

```sh
docker compose up --build
```

Интерфейс: <http://localhost:5173>, backend: <http://localhost:8080>. Остановка: `docker compose down`.
Сервис `backend` по умолчанию запускает `serve-native examples/physics/full-5000.json` (5000 домов);
другой сценарий выбирается через `HH_SCENARIO`, например компактный: `HH_SCENARIO=examples/physics/cascade.json`.
Профиль `simulation` (`docker compose --profile simulation up`) делает разовый прогон `run-native`
с записью JSONL в `./results` (по умолчанию `examples/physics/full.json`).

Локально нужны JDK 17, CMake, компилятор C++20 и Node.js/npm, совместимые с [frontend/package.json](frontend/package.json). Команды для PowerShell из корня проекта:

```powershell
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build --config Release
.\colony-dsl-parser\gradlew.bat -p colony-dsl-parser installDist
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat serve-native examples/physics/cascade.json 8080
```

Во втором терминале:

```powershell
Set-Location frontend
npm ci
npm run dev
```

На Linux/macOS используйте `./colony-dsl-parser/gradlew` и CLI без суффикса `.bat`.
`run-native SCENARIO OUT.jsonl` сохраняет полный прогон; `serve` и `run` используют эталонный JVM-исполнитель.

## Примеры и проверки

| Путь | Назначение |
|---|---|
| [cascade.json](examples/physics/cascade.json) | Повреждение сети → остывание → замерзание труб → ремонт и расходы |
| [full.json](examples/physics/full.json) | 300 домов, жители, роверы и отряды; все настройки мира заданы явно |
| [full-5000.json](examples/physics/full-5000.json) | Conformance-сценарий: те же настройки мира на 5000 домах, 20 028 контекстов |
| [settlement-5000.json](examples/physics/settlement-5000.json) | **Новый сценарий**: 5000 домов, Земной климат, Лес/Море, Летающие крокодилы, ПВО, Креатин |
| [settlement.colony](examples/physics/settlement.colony) | Работающие программы поведения |
| [examples/drafts](colony-dsl-parser/examples/drafts) | Черновики для неподдерживаемых видов; не рабочие примеры |

Проверки: Gradle `test`, CTest для `native/build`, `npm test` и `npm run build` в `frontend`,
`python conformance/run_vectors.py`. Полный список команд сборки, запуска и проверок — в [AGENTS.md](AGENTS.md);
предметный контракт языка — [docs/generated/contract.json](docs/generated/contract.json), его правила —
в [docs/spec/colony-language.md](docs/spec/colony-language.md).
