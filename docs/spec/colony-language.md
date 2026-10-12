# Язык Colony и сценарии

> **Статус:** спека · **Обновлять при:** любом изменении синтаксиса/семантики языка, каталога или формата сценария · **Аудитория:** авторы сценариев и разработчики · **Источник истины:** Colony.g4, Contracts.kt, Scenario.kt

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

Текущий предметный контракт — версия 9. `Human.medical_required` сообщает о необходимости
посещения медцентра; `Predator.attack_active` ограничивает окно наземной атаки.
Без соответствующей механики ядро возвращает `false` и `true` соответственно.
`Marine.visible_predators` содержит цели наземной обороны. Сценарии используют `GroundPredator`
и тип снимка `predator`. Поддержка дублирующего вида прежнего мира удалена;
детали несовместимости — [ADR 0009](../adr/0009-klyaksa-only.md).

Подсветка `.colony` для VS Code — [tools/vscode-colony](../../tools/vscode-colony/README.md);
она следует лексике `Colony.g4`, проверка типов по-прежнему выполняется компилятором.

## Каталоги, сценарии и манифесты

Каталог задаёт `sources` и шаблоны объектов; сценарий — каталог, `seed`, шаг, длительность и количества шаблонов.
Манифест разворачивает конкретные ID, параметры, родителей и координаты. Пути считаются от файла каталога/сценария.
`$instance` заменяется на `<prefix>-<номер>`; ID объекта — `<prefix>-<номер>/<роль>`.
С `world` наблюдения считает ядро; без него `view`/`changes` задают входы стенда. Совмещать эти источники нельзя.
Схема и лимиты: [Scenario.kt](../../colony-dsl-parser/src/main/kotlin/colony/runtime/Scenario.kt).

Готовые примеры: [examples/physics](../../examples/physics) (сценарии и `settlement.colony`),
[examples/integration](../../examples/integration), черновики — [colony-dsl-parser/examples/drafts](../../colony-dsl-parser/examples/drafts)
(не рабочие примеры).

## Colony settings

Файл `colony.settings` использует отдельный вход `settingsProgram` той же `Colony.g4`;
лексер, AST выражений, типы, проверка операторов и единицы измерения общие с `.colony`.
`.colony` объявляет поведение; `.settings` связывает его параметры и создаёт экземпляры.
В `settings Name { ... }` доступны `source "relative.colony";`, константы `let`,
`template Name { object role using Behavior { param name = expression; ... } }`,
`population Name as prefix { count = expression; }`, `seed`, `step`, `ticks` и блок `world`.
Имена настроек — мягкие ключевые слова и остаются допустимыми идентификаторами в поведении.

Основной сценарий Кляксы — [examples/physics/colony.settings](../../examples/physics/colony.settings),
эквивалентный `settlement-5000.json` по манифесту и физическим параметрам.
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
Записи в выражениях настроек и переопределение параметров на отдельной группе пока не реализованы.
Старые JSON-сценарии читаются совместимым загрузчиком; новые проекты могут использовать `.settings` без каталога JSON.
Команды `check`, `prepare`, `run`, `serve` принимают `.settings`; `prepare` создаёт отдельные `program.cvm` и `manifest.json`.
