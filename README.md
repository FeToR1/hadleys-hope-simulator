# Симулятор колонии

Учебный проект: Colony → компилятор ANTLR4/Kotlin → стековая ВМ C++ → ядро мира → React-дашборд.

**Новая цель — 5000 домов: Экосистема Лес/Море, Летающие крокодилы, ПВО и Креатиновая экономика.**
Посёлок масштабирован до 5000 домов (более 20 000 контекстов поведения). Пространственный индекс сокращает поиск до посещаемых ячеек и кандидатов; стоимость зависит от радиуса и плотности. Посёлок окружён зональным лесом (З, С, Ю) и морем (В). Ядро связывает угрозы, питание, конечные запасы защиты и служб, логистику креатина, ремонт и расходы. Проверенное покрытие и ограничения — в [отчёте аудита](docs/review-last-three-commits.md).

Документация:

- **[Архитектура экосистемы и Руководство (5000 домов)](docs/ECOSYSTEM_ARCHITECTURE.md)** — полное описание подсистем, схема взаимодействия, руководство для разработчиков и AI-моделей.
- [ТЗ для Кляксы](docs/klyaksa-requirements.md) — требования, границы и риски.
- [Проверка последних трёх коммитов](docs/review-last-three-commits.md) — потерянная оптимизация, исправления и результаты проверок.
- [Технический справочник](docs/technical-reference.md) — язык, исполнение, API и проверки.
- [CVM v2](docs/cvm-v2.md) — двоичный формат и текущий протокол нативной ВМ.
- [Оптимизации и замеры 5000 домов](docs/optimization-5000.md) — потоки, узкие места и варианты нативного исполнения.

## Ручные напасти

Кнопка **«Напасти»** рядом с вкладками карты открывает панель; повторный клик, крестик или Escape закрывают её.
Можно взорвать реактор с выбранным радиусом и уроном, запустить стаю летающих крокодилов или впустить
живых монстров из сценария внутрь посёлка, задав количество и длительность окна атаки.
Команды срабатывают на следующем шаге: на паузе нажмите **«Один шаг»** или продолжите симуляцию.
Перезапуск очищает очередь и восстанавливает исходный мир. Для работы панели нужны обновлённые backend и UI.

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

Скрипт собирает backend и запускает backend/UI в фоне на портах 8080/5173. Использует `settlement-5000.json`,
4 рабочих потока поведения, компактный поток наблюдений, общий JVM-процесс с максимумом heap 2 ГиБ и целевую скорость 10 тиков/с.
Точное число контекстов зависит от состава служб сценария; шаг модели — 1 с.
Логи и PID — `results/local-5000.json`, `results/*-5000*.log`. `-Workers N` меняет число рабочих потоков,
`-Speed 10` задаёт целевые 10 тиков/с, `-BackendPort`/`-FrontendPort` меняют порты, `-SkipBuild` использует готовую сборку.
`-Runtime native -Workers 1` включает общий нативный исполнитель; обычный запуск собирает его,
а `-SkipBuild` требует готовую ВМ. `-OpenBrowser` открывает браузер. Скрипт останавливает процессы
этого проекта и отказывается занимать порт, на котором остался посторонний слушатель.
Исторический замер `full-5000.json` на объединённом `development` с компактным кодированием дал около 6,22 тика/с без HTTP и браузера:
заданная скорость не гарантирует фактическую. Быстрый расчёт без промежуточных снимков достигает 10,47 тика/с
в коротком замере без записи журнала; условия и ограничения приведены в [отчёте](docs/optimization-5000.md#проверка-и-слияние-с-development).
Эти исторические числа не измеряют новый сценарий экосистемы. По умолчанию запуск использует режим `reference`.
`run-native` и `serve-native` используют общий пул нативных процессов:
байт-код загружается один раз на процесс, а каждый житель и прибор получает отдельный контекст. `HH_NATIVE_WORKERS`
задаёт размер пула (по умолчанию 1); `HH_REFERENCE_WORKERS` относится только к эталонному JVM-исполнителю.
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

Запуск frontend/backend на 300 домах через Docker:

```sh
docker compose up --build
```

Интерфейс: <http://localhost:5173>, backend: <http://localhost:8080>. Остановка: `docker compose down`.
По умолчанию запускается `examples/physics/full.json` с 300 домами; другой сценарий выбирается через `HH_SCENARIO`.
Для компактного запуска задайте `HH_SCENARIO=examples/physics/cascade.json`.

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
`run-native SCENARIO OUT.jsonl` сохраняет полный прогон; `serve` и `run` по умолчанию используют эталонный JVM-исполнитель.
Внутренний обмен ядро ↔ брокер ↔ C++ и конфигурация брокера используют двоичный формат;
JSON сохраняется во внешнем API и журналах. [Описание протоколов](docs/cvm-v2.md#9-частный-протокол-ядро-jvm--jvm-брокер).

## Примеры и проверки

| Путь | Назначение |
|---|---|
| [cascade.json](examples/physics/cascade.json) | Повреждение сети → остывание → замерзание труб → ремонт и расходы |
| [full.json](examples/physics/full.json) | 300 домов, жители, роверы и отряды; все настройки мира заданы явно |
| [full-5000.json](examples/physics/full-5000.json) | Conformance-сценарий: те же настройки мира на 5000 домах, 20 028 контекстов |
| [settlement-5000.json](examples/physics/settlement-5000.json) | **Новый сценарий**: 5000 домов, Земной климат, Лес/Море, Летающие крокодилы, ПВО, Креатин |
| [ecosystem-small.json](examples/physics/ecosystem-small.json) | 80 домов, короткие циклы угроз, уборка и лесная служба |
| [settlement.colony](examples/physics/settlement.colony) | Работающие программы поведения |
| [examples/drafts](colony-dsl-parser/examples/drafts) | Черновики для неподдерживаемых видов; не рабочие примеры |

Проверки: Gradle `test`, CTest для `native/build`, `npm test` и `npm run build` в `frontend`,
`python conformance/run_vectors.py`. Подробности и источники контрактов — в [справочнике](docs/technical-reference.md).
