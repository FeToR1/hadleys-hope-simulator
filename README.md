# Симулятор колонии

Учебный проект: Colony → компилятор ANTLR4/Kotlin → стековая ВМ C++ → ядро мира → React-дашборд.

**Новая цель — Клякса, 5000 домов. Пока обновлена только документация.** Код моделирует прежний сценарий LV-426: тепло, электричество, воду, жителей, атаки, ремонт, транспорт и расходы. Общие нативные исполнители и новые угрозы ещё не реализованы.

Документация:

- [ТЗ для Кляксы](docs/klyaksa-requirements.md) — требования, границы и риски.
- [Технический справочник](docs/technical-reference.md) — язык, исполнение, API и проверки.
- [CVM v2](docs/cvm-v2.md) — двоичный формат и текущий протокол нативной ВМ.

## Запуск

Самый короткий путь — Docker:

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
| [settlement.colony](examples/physics/settlement.colony) | Работающие программы поведения |
| [examples/drafts](colony-dsl-parser/examples/drafts) | Черновики для неподдерживаемых видов; не рабочие примеры |

Проверки: Gradle `test`, CTest для `native/build`, `npm test` и `npm run build` в `frontend`,
`python conformance/run_vectors.py`. Подробности и источники контрактов — в [справочнике](docs/technical-reference.md).
