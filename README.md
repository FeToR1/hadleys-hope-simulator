# Симулятор колонии

Учебный проект: Colony → компилятор ANTLR4/Kotlin → стековая ВМ C++ → ядро мира → React-дашборд.

**Новая цель — Клякса, 5000 домов.** Текущий мир LV-426 запускается на 5000 домах в общем JVM-процессе: сняты искусственные лимиты, добавлены рабочие потоки и индексы мира. Код моделирует тепло, электричество, воду, жителей, атаки, ремонт, транспорт и расходы. Общие нативные исполнители и новые угрозы ещё не реализованы.

Документация:

- [ТЗ для Кляксы](docs/klyaksa-requirements.md) — требования, границы и риски.
- [Технический справочник](docs/technical-reference.md) — язык, исполнение, API и проверки.
- [CVM v2](docs/cvm-v2.md) — двоичный формат и текущий протокол нативной ВМ.
- [Оптимизации и замеры 5000 домов](docs/optimization-5000.md) — потоки, узкие места и варианты нативного исполнения.

## Запуск

5000 домов на Windows, из корня проекта:

```powershell
.\scripts\start-5000.ps1
```

Скрипт собирает backend и запускает backend/UI в фоне на портах 8080/5173. Использует `full-5000.json`,
4 рабочих потока поведения, компактный поток наблюдений, общий JVM-процесс с максимумом heap 2 ГиБ и целевую скорость 10 тиков/с.
Мир содержит 20 028 контекстов; климат, мощности, службы, шаг 1 с и длительность суток сохранены из `full.json`.
Логи и PID — `results/local-5000.json`, `results/*-5000*.log`. `-Workers N` меняет число рабочих потоков,
`-Speed 10` задаёт целевые 10 тиков/с, `-BackendPort`/`-FrontendPort` меняют порты, `-SkipBuild` использует готовую сборку.
На этой машине свежий замер расчёта с компактным кодированием даёт около 4,92 тика/с без HTTP и браузера:
заданная скорость не гарантирует фактическую. Быстрый расчёт без промежуточных снимков достигает 8,75 тика/с
в коротком замере; условия и ограничения приведены в [отчёте](docs/optimization-5000.md#дополнительное-ускорение).
Это эталонный режим `reference`; текущий `serve-native` всё ещё создаёт процесс на сущность.
Карта объединяет дома и жителей в группы издалека, при приближении рисует только видимую область.

Для ручного запуска и замера после `installDist`:

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

Прежний нативный сценарий на 300 домах через Docker:

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
`run-native SCENARIO OUT.jsonl` сохраняет полный прогон; `serve` и `run` используют эталонный JVM-исполнитель.

## Примеры и проверки

| Путь | Назначение |
|---|---|
| [cascade.json](examples/physics/cascade.json) | Повреждение сети → остывание → замерзание труб → ремонт и расходы |
| [full.json](examples/physics/full.json) | 300 домов, жители, роверы и отряды; все настройки мира заданы явно |
| [full-5000.json](examples/physics/full-5000.json) | Те же настройки мира на 5000 домах, 20 028 контекстов; запускать через `serve` |
| [settlement.colony](examples/physics/settlement.colony) | Работающие программы поведения |
| [examples/drafts](colony-dsl-parser/examples/drafts) | Черновики для неподдерживаемых видов; не рабочие примеры |

Проверки: Gradle `test`, CTest для `native/build`, `npm test` и `npm run build` в `frontend`,
`python conformance/run_vectors.py`. Подробности и источники контрактов — в [справочнике](docs/technical-reference.md).
