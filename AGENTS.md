# AGENTS.md — инструкция для LLM-агентов

Прочитай этот файл целиком перед любой задачей — он заменяет самостоятельный обход репозитория.
Терминология — [docs/glossary.md](docs/glossary.md); запуск человеком — [README.md](README.md);
маршрут изучения проекта — [docs/onboarding.md](docs/onboarding.md).

## Что это за проект

Симулятор колонии (учебный проект ИТМО, «Системная инженерия»). Целевая нагрузка — посёлок на 5000 домов
(~20 028 контекстов поведения). Конвейер:

```
DSL Colony → компилятор (Kotlin + ANTLR4) → стековый байт-код .cvm
  → две ВМ: ReferenceVm (JVM, эталон) и hh-vm (C++20), кросс-проверяются conformance-векторами
  → ядро мира WorldKernel (физика, тепло, море, угрозы, креатин, TaskQueue, SpatialIndex)
  → HTTP/SSE сервер (:8080) → дашборд React 19 + TypeScript + Pixi.js/Three.js (:5173)
```

## Карта репозитория

| Путь | Что там |
|---|---|
| `colony-dsl-parser/` | Gradle-проект компилятора и runtime (Kotlin 2.2.21, JDK 17, ANTLR 4.13.2) |
| `colony-dsl-parser/src/main/antlr/Colony.g4` | Грамматика языка Colony |
| `colony-dsl-parser/src/main/kotlin/colony/` | Пакеты: `ast`, `parser`, `semantics` (в т.ч. `ContractDocument.kt`), `ir`, `bytecode`, `cvm`, `runtime` (`ReferenceVm`, `ObserverGateway`, `ProcessFleet`), `world` (ядро мира), `cli/Main.kt` — точка входа |
| `colony-dsl-parser/src/test/kotlin/colony/` | Тесты (35 файлов), включая `ConformanceVectorsTest`, `NativeConformanceTest`, `LargeScaleSettlementTest` |
| `native/` | hh-vm: C++20, CMake; `src/` (vm, reader, verify, random, protocol…), `tests/` |
| `frontend/` | Дашборд React 19 + Vite; `src/domain/types.ts` — типы кадров SSE, `src/components/` |
| `conformance/` | Векторы `*.json` + независимая Python-ВМ `run_vectors.py` |
| `examples/` | Сценарии: `physics/` (cascade, full, full-5000, settlement-5000, settlement.colony), `integration/` |
| `scripts/` | `start-5000.ps1` / `stop-5000.ps1` — запуск/остановка сценария «5000 домов» |
| `docs/` | Документация; карта — в [README](README.md#документация), правила ведения — [docs/contribution/documentation-policy.md](docs/contribution/documentation-policy.md) |
| `docs/generated/contract.json` | Генерируемый предметный контракт (язык/мир) — руками не править |
| `results/` | Артефакты запусков (gitignored) |

## Команды (Windows / Git Bash; на Linux — `./gradlew` и без `.bat`)

```sh
# Сборка нативной ВМ
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release && cmake --build native/build --config Release
ctest --test-dir native/build -C Release --output-on-failure          # тесты hh-vm

# Сборка CLI (дистрибутив)
colony-dsl-parser/gradlew.bat -p colony-dsl-parser installDist
CLI=colony-dsl-parser/build/install/colony-dsl-parser/bin/colony-dsl-parser.bat

# Тесты
colony-dsl-parser/gradlew.bat -p colony-dsl-parser test               # backend (native-тесты требуют hh-vm или HH_VM)
npm --prefix frontend ci && npm --prefix frontend test -- --run       # фронтенд
npm --prefix frontend run build                                       # production-сборка
python conformance/run_vectors.py                                     # независимая проверка векторов

# CLI (после installDist)
$CLI check SOURCE                       # компиляция одного файла
$CLI compile SOURCE OUTPUT              # JSON-представление байт-кода в файл
$CLI emit SOURCE OUT.cvm                # бинарный артефакт .cvm
$CLI disasm ARTIFACT.cvm                # дизассемблирование артефакта
$CLI prepare SCENARIO OUT_DIR           # program.cvm + manifest.json
$CLI broker PROGRAM_DIR                 # нативный брокер ВМ (используется run-native/serve-native)
$CLI run|run-native|run-fast SCENARIO [OUT.jsonl]   # прогоны (JVM / процессы hh-vm / fast)
$CLI serve|serve-native SCENARIO [PORT] # HTTP/SSE сервер (по умолчанию :8080)
$CLI benchmark|benchmark-live|benchmark-fast SCENARIO [TICKS]
$CLI conformance conformance            # перегенерация conformance-векторов (из корня репо)
$CLI contract docs/generated/contract.json          # перегенерация предметного контракта
```

Переменные окружения: `HH_VM` (путь к hh-vm), `HH_REFERENCE_WORKERS` (потоки поведения, обычно 4),
`HH_COMPACT_OBSERVER=1`, `HH_BIND_HOST`, `HH_ALLOWED_HOSTS`, `HH_SCENARIO` (Docker), `JAVA_OPTS`.

## Читать перед изменением X

| Что меняешь | Обязательно прочитать |
|---|---|
| Язык Colony (грамматика, семантика, компилятор) | [docs/spec/colony-language.md](docs/spec/colony-language.md), [docs/architecture.md](docs/architecture.md) |
| Байт-код, hh-vm, протокол ВМ, conformance | [docs/spec/cvm-format.md](docs/spec/cvm-format.md), [docs/adr/0001…](docs/adr/0001-conformance-troyaya-proverka.md), [0002](docs/adr/0002-processnaya-model-nativnoy-vm.md) |
| WorldKernel: физика, тепло, море, угрозы, креатин, TaskQueue, индекс | [docs/spec/runtime-tick.md](docs/spec/runtime-tick.md), [docs/architecture.md](docs/architecture.md), [ADR 0003](docs/adr/0003-spatial-index-i-kesh-svyazey.md), [0007](docs/adr/0007-fazovoe-chtenie-i-memoization.md) |
| Производительность / потоки / кэши | [docs/adr/0004…0007](docs/adr/), [docs/history/optimization-5000.md](docs/history/optimization-5000.md) |
| HTTP/SSE сервер или фронтенд | [docs/spec/http-api.md](docs/spec/http-api.md), [docs/adr/0005](docs/adr/0005-kompaktnyy-format-nablyudeniya.md) |
| Сценарии (`examples/`) | [docs/spec/colony-language.md](docs/spec/colony-language.md) (формат каталога/сценария), [README](README.md#примеры-и-проверки) |
| Docker / скрипты запуска | [README](README.md#запуск), `docker-compose.yml`, `scripts/*.ps1` |
| Архитектурное решение (новое или отмена старого) | [docs/adr/README.md](docs/adr/README.md) — сначала ADR, потом код |

## Обновить после изменения Y

| Что изменил | Действия (в том же коммите) |
|---|---|
| Язык/семантику/контракт (ContractDocument, Contracts.kt, Colony.g4) | Перегенерировать `contract docs/generated/contract.json`; `conformance conformance` при смене семантики; [docs/spec/colony-language.md](docs/spec/colony-language.md); CHANGELOG |
| Формат .cvm, опкоды, протокол ВМ | [docs/spec/cvm-format.md](docs/spec/cvm-format.md); несовместимое изменение — сначала ADR; обновить векторы; CHANGELOG |
| Конвейер тика, физику, PRNG | [docs/spec/runtime-tick.md](docs/spec/runtime-tick.md); векторы; CHANGELOG |
| Эндпоинты, формат снимка, политику истории SSE | [docs/spec/http-api.md](docs/spec/http-api.md); сверить `frontend/src/domain/types.ts`; CHANGELOG |
| Компоненты, границы, потоки, структуру файлов | [docs/architecture.md](docs/architecture.md); ADR при архитектурном решении; CHANGELOG |
| Новый термин предметной области | [docs/glossary.md](docs/glossary.md) |
| Видимое изменение или фикс | [CHANGELOG.md](CHANGELOG.md) |

## Правила

1. `docs/generated/contract.json` — только перегенерацией CLI `contract`; закоммить свежую версию (файл трекается).
2. Документация на русском; каждый документ в `docs/` имеет шапку «Статус / Аудитория / Обновлять при / Источник истины».
3. Один факт — одно место: термины — глоссарий, «почему» — ADR, «что изменилось» — CHANGELOG, команды — этот файл; остальное — ссылки.
4. Код + затронутые документы + CHANGELOG — атомарный коммит.
5. Изменение семантики обязано обновить conformance-векторы (тест подскажет команду перегенерации).
6. Статусы «не реализовано» в документах не удаляй при рефакторинге — они фиксируют границы; снимай только реализацией.
