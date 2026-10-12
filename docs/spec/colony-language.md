# Язык Colony и сценарии

> **Статус:** спека · **Обновлять при:** любом изменении синтаксиса/семантики языка или формата сценария · **Аудитория:** авторы сценариев и разработчики · **Источник истины:** Colony.g4, Contracts.kt, SettingsScenario.kt, Scenario.kt

Описание существующего прототипа. Новую цель определяет [ТЗ](../requirements.md), запуск — [README](../../README.md).
Точные поля и правила следует смотреть в связанных исходниках; документация не дублирует весь код.
См. также: исполнение — [runtime-tick.md](runtime-tick.md), двоичный артефакт — [cvm-format.md](cvm-format.md).

<a id="language"></a>

## Синтаксис и семантика

Язык задаёт поведение: `event`, `behavior ... for ...`, `param`, `state`, обработчики `on` и `every`;
внутри — `let`, присваивания, `if`, выражения и `send`. Типы и единицы проверяются семантически.

```colony
behavior HeaterControl for Heater {
    every 1s as regulate {
        if view.home_occupants > 0 && !view.broken && view.power_connected {
            power.request(2kW);
        } else {
            power.request(0W);
        }
    }
}
```

Синтаксис: [Colony.g4](../../colony-dsl-parser/src/main/antlr/Colony.g4). Поддерживаемые виды, наблюдения,
события и возможности: [Contracts.kt](../../colony-dsl-parser/src/main/kotlin/colony/semantics/Contracts.kt)
и генерируемый [contract.json](../generated/contract.json). Новое имя вида в грамматике само по себе не добавляет его физику.

Текущий предметный контракт — версия 11. `Human.medical_required` сообщает о необходимости
посещения медцентра; `Predator.attack_active` ограничивает окно наземной атаки.
Без соответствующей механики ядро возвращает `false` и `true` соответственно.
`Marine.visible_predators` содержит цели наземной обороны. Сценарии используют `GroundPredator`
и тип снимка `predator`. Поддержка дублирующего вида прежнего мира удалена;
детали несовместимости — [ADR 0009](../adr/0009-klyaksa-only.md).

Подсветка `.colony` для VS Code — [tools/vscode-colony](../../tools/vscode-colony/README.md);
она следует лексике `Colony.g4`, проверка типов по-прежнему выполняется компилятором.

## Каталоги, сценарии и манифесты

Сценарий `.settings` задаёт исходники, шаблоны объектов, `seed`, шаг, длительность и количества шаблонов.
Общие объявления выносятся в библиотеку `.settings` и подключаются через `include`.
Манифест разворачивает конкретные ID, параметры, родителей и координаты. Пути считаются от файла, объявившего `source` или `include`.
`$instance` заменяется на `<prefix>-<номер>`; ID объекта — `<prefix>-<номер>/<роль>`.
С `world` наблюдения считает ядро; без него `view` и конструкции `at` задают входы стенда. Совмещать эти источники нельзя.
Схема и лимиты: [Scenario.kt](../../colony-dsl-parser/src/main/kotlin/colony/runtime/Scenario.kt).

Готовые примеры: [examples/physics](../../examples/physics) (сценарии и `settlement.colony`),
[examples/integration](../../examples/integration), черновики — [colony-dsl-parser/examples/drafts](../../colony-dsl-parser/examples/drafts)
(не рабочие примеры).

## Colony settings

Файл `colony.settings` использует отдельный вход `settingsProgram` той же `Colony.g4`;
лексер, AST выражений, типы, проверка операторов и единицы измерения общие с `.colony`.
`.colony` объявляет поведение; `.settings` связывает его параметры и создаёт экземпляры.
В `settings Name { ... }` доступны `source "relative.colony";`, `include "relative.settings";`, константы `let`,
`template Name { object role using Behavior { param name = expression; ... } }`,
`population Name as prefix { count = expression; }`, `seed`, `step`, `ticks` и блок `world`.
Имена настроек — мягкие ключевые слова и остаются допустимыми идентификаторами в поведении.

Основной сценарий Кляксы — [examples/physics/colony.settings](../../examples/physics/colony.settings),
общие шаблоны — [catalog.settings](../../examples/physics/catalog.settings). Численность меняется
в `let houses` основного сценария, мощность обогревателя — в `let heaterPower` библиотеки.
Проверяемый малый пример — [examples/settings/colony.settings](../../examples/settings/colony.settings),
поведения — [behavior.colony](../../examples/settings/behavior.colony).
Объект выводит свой вид из `behavior ... for Kind`; обязательные параметры сверяются с объявлениями `.colony`,
ссылки — с видами созданных объектов. `parent` задаёт дом; `x`/`y` задают смещение с типом `Distance`.
`$instance` подставляется при развёртывании. Шаблон описывается один раз независимо от численности.
Группа может задать `columns`, `spacingX`, `spacingY`, `originX`, `originY`; без них сохраняется стандартная сетка.

Выражения настроек вычисляются один раз до запуска: литералы, ранее объявленные константы, скобки,
арифметика, сравнения и логика. Переполнение, нечисловые результаты и несовместимые размерности отвергаются.
Наблюдения `view`, время и вызовы функций в настройках недоступны: это динамические возможности поведения.
Типизированные поля требуют единиц (`step = 1s`, `param demand = 2kW`, `x = -2m`).
`world` повторяет имена блоков `WorldConfig`; размерности задаёт `worldSettingQuantity` в `SettingsScenario.kt`.
Составные коэффициенты, для которых в Colony пока нет размерности, задаются скалярами.
Списки в присваиваниях настроек поддерживаются: `mutations = ["armored", "swift", "venomous", "pack"];`; элементы используют общие выражения Colony.
Константные записи используют существующие схемы Colony и доступны также в `let`:
`let point: Position = Position { x: -2m, y: 1km };`. Поля сверяются со схемой, дубликаты и
неверные размерности отвергаются. `Target { id: "home-1/heater" }` допускает пропуск остальных
полей: стенд вычисляет их из актуальных объектов. Ссылки на цели проверяются после развёртывания.

`include` подключает только исходники, константы, шаблоны и другие библиотеки; библиотека
не задаёт `world`, населения или свойства запуска. Константы библиотек вычисляются перед локальными.
Циклы отвергаются; одна библиотека в ромбовидном графе подключается один раз.

Стенд без физического `world` может назначить изменение наблюдений на целочисленный тик:

```colony
at 5 for "home-1/house" { occupants = 0; }
at 10 for "predator-1/predator" {
    patrol_waypoint = Position { x: 400m, y: 300m };
    visible_infrastructure = [Target { id: "home-1/heater" }];
}
```

Тики должны лежать в пределах запуска, объект и наблюдение — существовать, значение — иметь
тип наблюдения. Вычисляемые стендом поля менять нельзя. Сочетание `world` и `at` запрещено.
Переопределение параметров на отдельной группе пока не реализовано.
JSON-каталоги и сценарии удалены; CLI принимает только `.settings` как сценарий.
Решение и границы переноса — [ADR 0011](../adr/0011-settings-only-scenarios.md).
Команды `check`, `prepare`, `run`, `serve` принимают `.settings`; `prepare` создаёт отдельные `program.cvm` и `manifest.json`.
