# Colony MVP — синтаксический контракт

## 1. Объявления верхнего уровня

```text
program      ::= { eventDecl | behaviorDecl } EOF

eventDecl    ::= "event" TypeName "{" { eventField } "}"
eventField   ::= Identifier ":" TypeRef ";"

behaviorDecl ::= "behavior" Identifier "for" TypeName "{" { behaviorMember } "}"
```

## 2. Элементы `behavior`

```text
enumDecl   ::= "enum" Identifier "{" Identifier { "," Identifier } [ "," ] "}"
paramDecl  ::= "param" Identifier ":" TypeRef ";"
stateDecl  ::= "state" Identifier ":" TypeRef "=" Expression ";"

ruleDecl   ::= onRule | everyRule
onRule     ::= "on" TypeName "(" Identifier ")" "as" Identifier Block
everyRule  ::= "every" DurationLiteral "as" Identifier Block
```

## 3. Операторы

```text
letStmt        ::= "let" Identifier [ ":" TypeRef ] "=" Expression ";"
ifStmt         ::= "if" Condition Block [ "else" ( "if" Condition Block | Block ) ]
assignStmt     ::= LValue "=" Expression ";"
sendStmt       ::= "send" TypeName "{" RecordFields "}" "to" Expression ";"
exprStmt       ::= Expression ";"

Condition      ::= "let" Identifier "=" Expression | Expression
```

## 4. Выражения

Приоритет операторов от высокого к низкому:

```text
postfix:     member access, call, index
unary:       ! + -
* / %
+ -
< <= > >=
== !=
&&
||
```

Постфиксные вызовы позволяют записывать как `nearest(...)`, так и выражения
вида `power.request(2kW)`. Создание записи имеет форму
`TypeName { field: expr, ... }`.

## 5. Ссылки на типы

Грамматика допускает встроенные имена типов, пользовательские имена и
обобщённые конструкции:

```text
Bool Int64 Real64 Duration Probability Rate Money
Temperature Power Energy Volume Distance Speed Health
Ref<T> Option<T> List<T>
```

Любое имя типа разбирается как часть синтаксической конструкции `TypeRef`.

## 6. Числа и единицы

Лексер хранит число и непосредственно следующий за ним суффикс единицы в
одном токене. В примерах языка используются:

```text
18degC  21degC  0W  2kW  2m  5mps  20hp  1s  1min  0.4per_s
```

Это позволяет использовать один и тот же синтаксический механизм для разных
числовых значений с единицами.

## 7. Имена сущностей

Названия конкретных объектов симуляции записываются как обычные имена типов.
Например:

```text
House
Heater
Kettle
Refrigerator
Rover
Human
Marine
Computer
HeatingNode
WaterNode
InternetNode
GarbageTruck
AtomicReactor
Xenomorph
```

Добавление нового имени сущности не требует изменения грамматики, поскольку
после `for` используется `TypeName`.

## 8. Граница синтаксического фронтенда

Результатом работы этого проекта является:

```text
source text
    ↓
lexer
    ↓
parser
    ↓
parse tree
    ↓
AstBuilder
    ↓
Kotlin AST + SourceSpan
```

AST передаётся следующему этапу компилятора.
