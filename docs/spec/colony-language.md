# Язык Colony и сценарии

> **Статус:** спека · **Аудитория:** разработчики компилятора, авторы сценариев, LLM-агенты · **Обновлять при:** любом изменении синтаксиса/семантики языка, каталога или формата сценария · **Источник истины:** этот файл описывает язык; точные определения — [Colony.g4](../../colony-dsl-parser/src/main/antlr/Colony.g4), [Contracts.kt](../../colony-dsl-parser/src/main/kotlin/colony/semantics/Contracts.kt) и генерируемый [contract.json](../generated/contract.json)

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

## Каталоги, сценарии и манифесты

Каталог задаёт `sources` и шаблоны объектов; сценарий — каталог, `seed`, шаг, длительность и количества шаблонов.
Манифест разворачивает конкретные ID, параметры, родителей и координаты. Пути считаются от файла каталога/сценария.
`$instance` заменяется на `<prefix>-<номер>`; ID объекта — `<prefix>-<номер>/<роль>`.
С `world` наблюдения считает ядро; без него `view`/`changes` задают входы стенда. Совмещать эти источники нельзя.
Схема и лимиты: [Scenario.kt](../../colony-dsl-parser/src/main/kotlin/colony/runtime/Scenario.kt).

Готовые примеры: [examples/physics](../../examples/physics) (сценарии и `settlement.colony`),
[examples/integration](../../examples/integration), черновики — [colony-dsl-parser/examples/drafts](../../colony-dsl-parser/examples/drafts)
(не рабочие примеры).
