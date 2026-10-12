# Архитектура: Симуляция Поселка (5000 домов, Экосистема, ПВО и Креатин)

> **Статус:** актуально · **Обновлять при:** изменении компонентов, их границ, потоков данных или структуры кодовой базы · **Аудитория:** разработчики · **Источник истины:** компилятор, runtime, WorldKernel, frontend

Документ описывает архитектуру, взаимосвязи подсистем и алгоритмы для разработчиков команды и LLM/AI-агентов.
Смежные документы: [requirements.md](requirements.md) (цели и границы), [spec/](spec/colony-language.md) (язык, исполнение, формат .cvm, HTTP/SSE),
[adr/](adr/) (почему устроено так), [glossary.md](glossary.md) (термины).

Компилятор Colony строит типизированный IR и единственный исполняемый артефакт CVM2.
`hh-vm` (C++20) выполняет изолированные контексты в общем пуле процессов; JVM содержит компилятор,
оркестратор `SimulationRun`, брокер и `WorldKernel`, но не интерпретатор инструкций.
Ядро разрешает намерения и считает физику; `ObserverGateway` публикует HTTP/SSE (:8080), React рисует карту (:5173).
Переход на один исполнитель и декларации `.settings` — [ADR 0010](adr/0010-single-cvm2-and-settings.md).

Зональную экологию (лес, навоз, планктон, состояние людей) реализует `EcosystemController`, физические
последствия применяет `WorldKernel`; механики отключены по умолчанию и включаются сценарием.
Проверенное покрытие и исправления — в [отчёте аудита](history/review-last-three-commits.md);
численные примеры ниже — исходные значения обновления, а не замер актуального сценария.

---

## 1. Механики экосистемы

Модель Кляксы: что добавлено и по каким правилам работает.

| Блок | Что делает | Ключевые правила и цифры |
|---|---|---|
| **Масштабирование** | Сетка расширена с 300 до 5000 домов: дома, обогреватели, чайники, жители, бригады, роверы — более 20 000 параллельных контекстов | 2D Spatial Hash Grid (`SpatialIndex`) сокращает проверки близости/видимости/коллизий до посещаемых ячеек и найденных кандидатов — стоимость зависит от радиуса и плотности, а не от $O(N^2)$ полного перебора |
| **Топология и биомы** | Атмосфера земная, комфортная (+19 °C), свободное дыхание по всей территории; лес окружает с З, С и Ю; море — с В | Из леса появляются воздушные угрозы; море ограничивает с четвёртой стороны |
| **Море (`SeaController`)** | Эрозионный урон домам прибрежной полосы; плотное смещающееся облако тумана (~100×100 домов: 1200×1400 м) зарождается у восточной границы и дрейфует по диагонали к лесу | Внутри эллипса тумана рабочие без респиратора получают урон от удушья, ПВО коррозируется и отключается; вне облака — ясная видимость |
| **Угрозы (`FlyingCrocodile`)** | Вылетают из случайных точек леса, летят над посёлком к морю по траектории с шумом Перлина (`TrajectoryNoise`); от точки разворота (`turnPoint`) возвращаются во второй фазе (`FlightPhase.RETURNING`) к уникальной точке выхода (`exitPoint`) с независимым шумом; по достижении леса или уничтожении — деспавн в пул `CrocodilePool` | Интервал появления 17.5 с (удвоенная частота); пул на 128 слотов |
| **Защита (`AirDefenseUnit`)** | Стационарные орудия по периметру и на крышах: сканируют воздух, сбивают или отклоняют крокодилов | В зоне морского тумана переходят в `broken` до починки |
| **Экономика креатина (`CreatineManager`)** | Добыча в шахте, перевозка шахта → склад → медцентр, продажа (`creatine_sale`), лечение раненых | Синергия добычи $S = 1.0 + 9.0 \times \min(1.0, W/5000.0)$: 1 рабочий — $1.0\times$, 750 рабочих — $2.35\times$, 5000 — максимум $10.0\times$. Смены 1200 с = 750 с работы + 450 с возврата, фазы слотами `(slot × 37) % 1200`. Лечение при `health < 90` до 100 hp за счёт медзапаса креатина (пешком или на такси-роверах) |
| **Флот роверов** | 150 машин со строгими ролями: 50 ремонтных `crew-1..50` (сетка $5\times10$ постов — только ремонт домов, ПВО и коммуникаций), 50 грузовых `cargo-1..50` (25 возят сырой креатин `Mine`↔`Depository`, 25 — очищенный `Depository`↔`MedicalCenter`), 50 такси `transport-1..50` (жители на работу/домой и оперативная доставка раненых в медцентр) | Ремонтные никогда не перевозят пассажиров и грузы |
| **Визуализация (Pixi.js + SSE)** | SVG-иконки всех сущностей с метками роверов `РЕМ`/`ГРУЗ`/`ТАКСИ`; туман — виртуальная погодная сущность `weather/sea_fog` (полупрозрачный многослойный эллипс в реальном времени) | В легенде и слоях — местные хищники; дублирующая модель прежнего мира удалена |

Как среда и угрозы взаимодействуют с посёлком:

```mermaid
flowchart LR
    FOREST["Лес (З, С, Ю)<br/>источник воздушных угроз"]
    SEA["Море (В)<br/>приливы, эрозия берега"]
    FOG["Облако тумана<br/>~100×100 домов (1200×1400 м)"]
    TOWN["Посёлок: 5000 домов,<br/>шахта, склад, медцентр, 150 роверов"]
    CROC["Летающие крокодилы<br/>(интервал 17.5 с)"]
    AD["ПВО периметра и крыш"]
    TURN["Точка разворота над морем"]

    FOREST -->|"вылет, шум Перлина"| CROC
    CROC -->|"полёт над посёлком"| TURN
    TURN -->|"обратный путь к своей точке выхода"| FOREST
    CROC -.->|"сброс навоза"| TOWN
    AD -.->|"сбитие или отклонение"| CROC
    SEA -->|"зарождение у восточной границы"| FOG
    FOG -->|"дрейф по диагонали на запад"| FOREST
    FOG -.->|"урон без респиратора"| TOWN
    FOG -.->|"коррозия до починки"| AD
    SEA -.->|"эрозия берега"| TOWN
```

---

## 2. Диаграммы

### 2.1. Конвейер обработки: от DSL до дашборда

```mermaid
flowchart LR
    SRC["*.colony"] --> P["ANTLR4 → AST → проверка типов"]
    P --> IR["Типизированный IR"]
    IR --> C["CVM2: program.cvm"]
    SETTINGS["colony.settings<br/>та же грамматика выражений"] --> BIND["Параметры, шаблоны, мир"]
    P --> BIND
    BIND --> MANIFEST["RunManifest + WorldConfig"]
    C --> HH["Брокер → hh-vm (C++20)"]
    MANIFEST --> RUN["SimulationRun"]
    RUN -->|"VmFrame: view + события"| HH
    HH -->|"VmResult: намерения + события + состояние"| RUN
    RUN --> WK["WorldKernel"]
    WK --> GW["ObserverGateway: HTTP/SSE"]
    GW --> UI["React + Pixi.js/Three.js"]
```

Проекты по слоям: [colony-dsl-parser/](../colony-dsl-parser) (компилятор, runtime, ядро мира), [native/](../native) (hh-vm),
[frontend/](../frontend) (дашборд). Детали: язык — [spec/colony-language.md](spec/colony-language.md),
исполнение и тик — [spec/runtime-tick.md](spec/runtime-tick.md), формат .cvm — [spec/cvm-format.md](spec/cvm-format.md),
наблюдение — [spec/http-api.md](spec/http-api.md).

### 2.2. Компоненты мира и интерфейса

```mermaid
flowchart TD
    subgraph Environment ["Среда и Биомы"]
        Forest["Лесной массив (З, С, Ю)"]
        Sea["Море (Восток)"]
        SeaCtrl["SeaController\n(Эрозия берега, Цикл тумана)"]
        Sea -->|"состояние и цикл"| SeaCtrl
    end

    subgraph DefenseAndThreatsSub ["Воздушное пространство"]
        CrocPool["CrocodilePool\n(Object Pool)"]
        Crocs["FlyingCrocodile\n(Шум Перлина / Бомбардировка)"]
        ADS["AirDefenseUnit\n(ПВО периметра и крыш)"]
        Forest -->|"источник вылетов"| Crocs
        CrocPool -->|"выдача слота при спавне"| Crocs
        Crocs -->|"возврат слота при деспавне"| CrocPool
        ADS -.->|"Сбитие / Отклонение"| Crocs
        SeaCtrl -.->|"Отключение туманом"| ADS
    end

    subgraph SimulationCore ["Ядро мира (WorldKernel)"]
        Spatial["SpatialIndex\n(2D Spatial Hash Grid)"]
        Tasks["TaskQueue\n(Очередь ремонта строений и ПВО)"]
        Creatine["CreatineManager\n(Склад, Продажа, Исцеление)"]
    end

    subgraph Logistics ["Логистика и Население"]
        Miners["Шахтёры\n(Добыча креатина)"]
        Mine["Шахта креатина"]
        Depot["Склад креатина"]
        MedCenter["Медицинский центр"]
        Crews["Ремонтные бригады\n(роверы crew-*)"]
        Taxi["Такси-роверы (transport-*)\nжители на работу/домой, раненые — в медцентр"]
        Miners -->|"работают"| Mine
        Mine -->|"сырой креатин<br/>(грузовые cargo-*)"| Depot
        Depot -->|"очищенный креатин<br/>(грузовые cargo-*)"| MedCenter
        MedCenter -.->|"Исцеление"| Miners
        Taxi -->|"доставка раненых"| MedCenter
        Tasks -->|"задачи ремонта"| Crews
        Crews -.->|"Ремонт"| ADS
    end

    subgraph UI ["Интерфейс мониторинга (React + Pixi.js)"]
        Map["SettlementMap\n(Биомы, Сетка, ПВО, Крокодилы, Туман)"]
        Sidebar["Sidebar & SpendPanel\n(Метрики, Доход от креатина, Аттрактор)"]
    end

    SeaCtrl -.->|"эрозия ломает строения — задачи ремонта"| Tasks
    Crocs -.->|"бомбардировка ломает строения — задачи ремонта"| Tasks
    SimulationCore -->|"снимки тиков"| UI
```

### 2.3. Проверка исполнителя

<a id="vectors"></a>

`conformance/*.json` хранит CVM2 в base64, входные кадры и зафиксированные ожидаемые результаты.
Kotlin-тесты и CLI повторяют их на `hh-vm`; `NativeConformanceTest` дополнительно пересобирает исходники
и сохраняет двоичные сессии. Python запускает повторение через CLI и независимо проверяет SHA-256 PRNG.
Это регрессионные проверки одного исполнителя, а не независимые реализации ВМ.
Ожидания при удалении v1 сохранены без изменения; смена формата конверта — версия conformance 2.
Команды — [AGENTS.md](../AGENTS.md).

---

Между слоями используются AST (`colony.ast`), `SemanticModel` (типы и разрешённые имена), IR (`colony.ir`),
`CompiledProgram` (CVM2 и сведения для проверки привязок), `PreparedRun`/`RunManifest`/`WorldConfig` (экземпляры и мир),
`VmFrame`/`VmResult` (граница исполнения), `TickSnapshot` (наблюдение). `colony.compiler` связывает стадии;
`colony.runtime.SettingsScenario` по AST настроек строит модель сценария; `SettingsPackage` разрешает подключения библиотек.
JSON-загрузчик сценариев удалён ([ADR 0011](adr/0011-settings-only-scenarios.md)).

## 3. Структура кодовой базы

| Путь к файлу | Назначение и ключевые компоненты |
|---|---|
| [`colony-dsl-parser/src/main/kotlin/colony/world/SpatialIndex.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/SpatialIndex.kt) | 2D Spatial Hash Grid. Индексация координат, поиск соседей в посещаемых ячейках радиуса `queryRadius`. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/EcosystemConfig.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/EcosystemConfig.kt) | Настройки зональной экосистемы: лес, навоз, планктон, состояние людей; конечные запасы. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/EcosystemController.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/EcosystemController.kt) | Зональная экология: служебные цели, факты и повреждения для ядра. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/Disaster.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/Disaster.kt) | Ручные напасти: взрыв реактора, запуск крокодилов и вторжение монстров (`POST /control/disaster`). |
| [`colony-dsl-parser/src/main/kotlin/colony/world/SeaController.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/SeaController.kt) | Контроллер моря: эрозия прибрежных строений, цикл морского тумана, зона поражения. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/DefenseAndThreats.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/DefenseAndThreats.kt) | Классы `TrajectoryNoise` (1D Perlin), `FlyingCrocodile`, пул `CrocodilePool`, орудия `AirDefenseUnit`. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/CreatineManager.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/CreatineManager.kt) | Логика экономики креатина: учёт добычи, коммерческая продажа в гроссбух, исцеление жителей. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/TaskQueue.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/TaskQueue.kt) | Глобальная очередь ремонтных задач с привязкой к координатам и spatial-индексу. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/Topology.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/Topology.kt) | Генерация топологии: дома, ЛЭП, водопровод, склад, медцентр, ПВО по периметру и крышам. |
| [`colony-dsl-parser/src/main/kotlin/colony/world/WorldKernel.kt`](../colony-dsl-parser/src/main/kotlin/colony/world/WorldKernel.kt) | Главный физический цикл: интеграция моря, угроз, ПВО, креатина, респираторов и ремонта. |
| [`examples/physics/colony.settings`](../examples/physics/colony.settings) | Конфигурация сценария на 5000 домов: земной климат (+19 °C), активное море, ПВО, креатин. |
| [`frontend/src/domain/mapPresentation.ts`](../frontend/src/domain/mapPresentation.ts) | Определение типов `air_defense`, `crocodile`, `depository`, `medical_center` и их SVG-иконок. |
| [`frontend/src/components/SettlementMap.tsx`](../frontend/src/components/SettlementMap.tsx) | Отрисовка карты на Pixi.js: отображение леса, моря, динамического тумана, юнитов ПВО и угроз. |
| [`frontend/src/components/Sidebar.tsx`](../frontend/src/components/Sidebar.tsx) | Информационная панель сущностей: состояние ПВО, запас креатина, урон от моря, респираторы. |
| [`frontend/src/components/SpendPanel.tsx`](../frontend/src/components/SpendPanel.tsx) | Финансовый отчёт с отображением строк дохода от экспорта креатина (`creatine_sale`). |

---

### Редактор Colony

[`tools/vscode-colony`](../tools/vscode-colony/README.md) — декларативное расширение VS Code.
`package.json` связывает `.colony` и `.settings` с языком, `syntaxes/colony.tmLanguage.json` задаёт TextMate-токены,
`language-configuration.json` — комментарии, скобки и отступы. Лексика следует `Colony.g4`.
Скрипт `scripts/install-colony-vscode.ps1` упаковывает VSIX и по флагу устанавливает его локально.
Проверки используют TextMate/Oniguruma. Компиляция и семантическая диагностика остаются в CLI;
language server пока не реализован.

## 4. Как запускать проект

Способы запуска — в [README](../README.md#запуск): VS Code в один клик (задачи
[`.vscode/tasks.json`](../.vscode/tasks.json)), PowerShell-скрипты `scripts/start-5000.ps1` / `stop-5000.ps1`,
Docker (`docker compose up --build`) и ручной запуск. Здесь запуск не дублируется.
`scripts/build-native.ps1` объединяет сборку C++ ВМ для PowerShell-запуска и задач VS Code;
выбор Windows CMake и восстановление несовместимого кэша выполняются в этом скрипте.

## 5. Запуск тестов и проверка работоспособности

Полный список команд сборки, запуска и проверок (Gradle, CTest, Vitest, conformance-векторы, регенерация
контракта) — в [AGENTS.md](../AGENTS.md). Интеграционный тест крупного масштаба:
[`LargeScaleSettlementTest`](../colony-dsl-parser/src/test/kotlin/colony/world/LargeScaleSettlementTest.kt)
(5000 домов, spatial-индекс, эрозия моря, туман, ПВО, крокодилы, экономика креатина).

---

## 6. Руководство по расширению для разработчиков и AI-моделей

При добавлении новых сущностей и механик — пять шагов конвейера:

1. **Конфигурация** — [WorldConfig.kt](../colony-dsl-parser/src/main/kotlin/colony/world/WorldConfig.kt): дата-класс настроек с валидацией + поле в `WorldConfig` с `enabled = false` (обратная совместимость conformance-тестов).
2. **Топология** — [Topology.kt](../colony-dsl-parser/src/main/kotlin/colony/world/Topology.kt): новый `FixtureKind` при необходимости и размещение в `buildTopology(...)`.
3. **Физика** — [WorldKernel.kt](../colony-dsl-parser/src/main/kotlin/colony/world/WorldKernel.kt): шаг обновления в `step(...)`; проверки радиуса — только `spatialIndex.queryRadius(...)`, не перебором; события `events += ...`, проводки `postings += ...`; метрики сущности — в `view(...)`.
4. **Представление** — [types.ts](../frontend/src/domain/types.ts): вид в `KNOWN_ENTITY_TYPES`; [mapPresentation.ts](../frontend/src/domain/mapPresentation.ts): цвет/размер/SVG в `MAP_TYPES`, движущиеся — в `MOBILE_LAYERS`.
5. **UI** — [SettlementMap.tsx](../frontend/src/components/SettlementMap.tsx): tooltip и легенда; [Sidebar.tsx](../frontend/src/components/Sidebar.tsx): карточка сущности.
