# Симулятор колонии

Учебный проект ИТМО «Системная инженерия»: Colony → компилятор ANTLR4/Kotlin → стековая ВМ C++ → ядро мира → React-дашборд.
Цель — посёлок на 5000 домов (более 20 000 контекстов поведения): экосистема Лес/Море, летающие крокодилы,
ПВО и креатиновая экономика. Пространственный индекс сокращает поиск до посещаемых ячеек и кандидатов
(стоимость зависит от радиуса и плотности); посёлок окружён зональным лесом (З, С, Ю) и морем (В).
Ядро связывает угрозы, питание, конечные запасы защиты и служб, логистику креатина, ремонт и расходы.
Проверенное покрытие и ограничения — в [отчёте аудита](docs/history/review-last-three-commits.md).

**Первое знакомство → [docs/onboarding.md](docs/onboarding.md)** — маршрут одного прохода (~20 минут).

## Документация

| Документ | Когда читать |
|---|---|
| [Онбординг](docs/onboarding.md) | Первое знакомство: маршрут одного прохода |
| [Архитектура](docs/architecture.md) | Как устроена система: подсистемы, диаграммы, структура кода, как расширять |
| [ТЗ «Клякса»](docs/requirements.md) | Цели, требования, границы, критерии приёмки |
| [Глоссарий](docs/glossary.md) | Термины: Клякса, тик, контекст поведения, ровер, креатин… |
| Спеки: [язык Colony](docs/spec/colony-language.md) · [тик и мир](docs/spec/runtime-tick.md) · [формат .cvm](docs/spec/cvm-format.md) · [HTTP/SSE](docs/spec/http-api.md) | Перед изменениями в соответствующей области |
| [ADR](docs/adr/README.md) | Почему устроено так, а не иначе |
| [CHANGELOG](CHANGELOG.md), [история оптимизаций](docs/history/optimization-5000.md), [отчёт аудита слияний](docs/history/review-last-three-commits.md) | Что и когда менялось; замеры и проверки |
| [AGENTS.md](AGENTS.md), [правила документации](docs/contribution/documentation-policy.md) | Работа LLM-агентов; ведение документации |

## Запуск

**VS Code — в один клик.** `Ctrl + Shift + B` → «1. Запуск симулятора (5000 домов + Экосистема)»:
задачи [.vscode/tasks.json](.vscode/tasks.json) останавливают прежние процессы проекта, собирают и запускают всё.
UI: <http://localhost:5173>, бэкенд: <http://localhost:8080>.

**PowerShell.**

```powershell
.\scripts\start-5000.ps1 -Scenario examples/physics/settlement-5000.json   # старт
.\scripts\stop-5000.ps1                                                   # остановка
```

Собирает backend и запускает backend/UI в фоне на портах 8080/5173. Сценарий — из `-Scenario`
(по умолчанию `settlement-5000.json`); точное число контекстов зависит от состава служб сценария, шаг модели — 1 с.
Параметры: `-Workers` (потоки), `-Speed`, `-BackendPort`, `-FrontendPort`, `-SkipBuild`, `-OpenBrowser`;
`-Runtime native -Workers 1` включает общий нативный исполнитель (обычный запуск собирает его,
`-SkipBuild` требует готовую ВМ). Скрипт останавливает только процессы этого проекта и отказывается
занимать порт с посторонним слушателем. Логи и PID — `results/local-5000.json`, `results/*-5000*.log`.

`run-native` и `serve-native` используют общий пул нативных процессов: байт-код загружается один раз
на процесс, каждый житель и прибор получает отдельный контекст. `HH_NATIVE_WORKERS` задаёт размер пула
(1–32, по умолчанию 1); `HH_REFERENCE_WORKERS` относится только к эталонному JVM-исполнителю.
Исторический замер `full-5000.json` на объединённом `development` — около 6,22 тика/с (fast до 10,47)
без HTTP и браузера; эти числа не измеряют новый сценарий экосистемы (2,85 тика/с, [отчёт](docs/history/optimization-5000.md#проверка-и-слияние-с-development)).
Карта объединяет дома и жителей в группы издалека, при приближении рисует только видимую область.

**Docker.**

```sh
docker compose up --build        # остановка: docker compose down
```

Backend по умолчанию — `serve-native examples/physics/full-5000.json`; другой сценарий — через `HH_SCENARIO`
(компактный: `HH_SCENARIO=examples/physics/cascade.json`). Профиль `simulation` (`docker compose --profile simulation up`)
делает разовый прогон `run-native` на 300 домах (`full.json`) с записью JSONL в `./results`.
UI: <http://localhost:5173>, backend: <http://localhost:8080>.

## Ручные напасти

Кнопка **«Напасти»** рядом с вкладками карты открывает панель (закрытие — крестик или Escape).
Можно взорвать реактор с выбранным радиусом и уроном, запустить стаю летающих крокодилов или впустить
живых монстров сценария, задав количество и длительность окна атаки. Команды срабатывают на следующем
шаге: на паузе нажмите «Один шаг» или продолжите симуляцию. `reset` очищает очередь и восстанавливает
исходный мир. Протокол — [docs/spec/http-api.md](docs/spec/http-api.md); нужны обновлённые backend и UI.

<details>
<summary><b>Замеры, env-переменные и ручной запуск</b></summary>

Требования: JDK 17, CMake, компилятор C++20 и Node.js/npm, совместимые с [frontend/package.json](frontend/package.json).

```powershell
# Ручной запуск и замеры (после installDist; на Windows пересборка требует остановки работающего сервера)
$env:JAVA_OPTS = '-Xms512m -Xmx2g'
$env:HH_REFERENCE_WORKERS = '4'
$env:HH_COMPACT_OBSERVER = '1'
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat benchmark examples/physics/full-5000.json 120
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat benchmark-live examples/physics/full-5000.json 120
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat benchmark-fast examples/physics/full-5000.json 120
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat serve examples/physics/full-5000.json 8080

# Расчёт без карты: все тики, полный снимок только в конце; с файлом — журнал фактов
# (события и проводки каждого тика, промежуточные кадры full=false), без файла — JSON-итог с контрольной суммой
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat run-fast examples/physics/full-5000.json

# Сборка с нуля
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build --config Release
.\colony-dsl-parser\gradlew.bat -p colony-dsl-parser installDist
.\colony-dsl-parser\build\install\colony-dsl-parser\bin\colony-dsl-parser.bat serve-native examples/physics/cascade.json 8080

Set-Location frontend
npm ci
npm run dev
```

- Без `HH_REFERENCE_WORKERS` эталонный исполнитель использует один рабочий поток. Ручной `serve` начинает
  прогон при подключении наблюдателя или `POST /control/resume`.
- Искусственные потолки 10 000 сущностей и 1 000 000 тиков сняты; количества — положительные/неотрицательные
  `Int`, общий размер манифеста проверяется до переполнения.
- `HH_COMPACT_OBSERVER=1` отключает обязательное кодирование полных кадров на каждом шаге сервера;
  UI запрашивает `/stream?format=compact`, обычный `/stream` и JSONL сохраняют полный формат.
- `run-native SCENARIO OUT.jsonl` сохраняет полный прогон; `serve` и `run` по умолчанию используют
  эталонный JVM-исполнитель.
- Внутренний обмен ядро ↔ брокер ↔ C++ и конфигурация брокера используют двоичный формат; JSON остаётся
  во внешнем API и журналах. [Описание протоколов](docs/spec/cvm-format.md#9-частный-протокол-ядро-jvm--jvm-брокер).
- На Linux/macOS используйте `./colony-dsl-parser/gradlew` и CLI без суффикса `.bat`.

</details>

## Примеры сценариев

| Сценарий | Что показывает |
|---|---|
| [settlement-5000.json](examples/physics/settlement-5000.json) | Полная экосистема: 5000 домов, лес/море, крокодилы, ПВО, креатин |
| [full-5000.json](examples/physics/full-5000.json) | Те же настройки мира на 5000 домов — 20 028 контекстов (замеры, conformance) |
| [full.json](examples/physics/full.json) | 300 домов: компактный полный набор мира |
| [cascade.json](examples/physics/cascade.json) | Каскад: повреждение сети → остывание → замерзание труб → ремонт и расходы |
| [ecosystem-small.json](examples/physics/ecosystem-small.json) | 80 домов: короткие циклы угроз, уборка и лесная служба |

Программы поведения — [settlement.colony](examples/physics/settlement.colony); черновики неподдерживаемых
видов (не рабочие примеры) — [colony-dsl-parser/examples/drafts](colony-dsl-parser/examples/drafts).

## Проверки

Gradle `test`, CTest для `native/build`, `npm test` и `npm run build` в `frontend`,
`python conformance/run_vectors.py`. Полный список команд и переменных окружения — [AGENTS.md](AGENTS.md);
предметный контракт языка — генерируемый [docs/generated/contract.json](docs/generated/contract.json).
