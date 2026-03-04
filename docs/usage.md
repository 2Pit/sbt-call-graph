# sbt-graph-explorer — Usage Guide

SBT-плагин для построения call graph Scala-проекта и навигации по нему через `sbtn`-команды.

---

## Установка

### 1. Опубликовать плагин локально

В директории `sbt-graph-exporter`:

```
analyzer/publishLocal; plugin/publishLocal
```

### 2. Добавить в целевой проект

`project/plugins.sbt`:

```scala
addSbtPlugin("me.peter" % "sbt-graph-explorer" % "0.1.0-SNAPSHOT")
```

`build.sbt` — включить на нужном модуле:

```scala
.enablePlugins(GraphExplorerPlugin)
```

Пример для `blank-slate-server`:

```scala
lazy val studyWs = (project in file("srs-study-ws"))
  .enablePlugins(BuildInfoPlugin, GraphExplorerPlugin)
.
..
```

### 3. Сгенерировать SemanticDB-артефакты

SemanticDB уже включён через `sbt-scalafix`. Достаточно скомпилировать:

```
compile
```

---

## Команды

### `graphIndex` — диагностика графа

Показывает количество вершин и рёбер в текущем кэше.

```
sbtn "studyWs/graphIndex"
```

Результат записывается в `target/graph-last-result.json`:

```json
{
  "status": "loaded at ...",
  "nodes": 1823,
  "edges": 4201
}
```

---

### `graphVia <vertex> [--depth N]` — вызывающие и вызываемые

Кто вызывает метод и что он сам вызывает. `--depth` задаёт количество транзитивных уровней в каждую сторону (default: 2).

```
sbtn "studyWs/graphVia sreo/session/SessionLive#close()."
sbtn "studyWs/graphVia sreo/session/SessionLive#close(). --depth 3"
```

```json
{
  "vertex": "sreo/session/SessionLive#close().",
  "callers": [
    {
      "id": "sreo/session/SessionLive#closeOnResult().",
      "displayName": "closeOnResult",
      "file": "srs-study-ws/src/main/scala/sreo/session/SessionsLive.scala",
      "startLine": 113,
      "endLine": 120
    }
  ],
  "callees": [
    {
      "id": "sreo/session/SessionLive#closeSession().",
      "displayName": "closeSession",
      "file": "srs-study-ws/src/main/scala/sreo/session/SessionsLive.scala",
      "startLine": 92,
      "endLine": 98
    }
  ]
}
```

---

### `graphPath <from> <to>` — путь между двумя методами

BFS-поиск всех путей от `from` до `to`.

```
sbtn "studyWs/graphPath sreo/session/SessionLive#closeOnResult(). sreo/session/SessionLive#closeSession()."
```

Опциональные параметры:

```
sbtn "studyWs/graphPath A B --maxDepth 15 --maxPaths 50"
```

Defaults: `maxDepth=20`, `maxPaths=100`.

```json
{
  "found": true,
  "truncated": false,
  "from": "sreo/session/SessionLive#closeOnResult().",
  "to": "sreo/session/SessionLive#closeSession().",
  "paths": [
    [
      {
        "id": "sreo/session/SessionLive#closeOnResult().",
        "displayName": "closeOnResult",
        "file": "srs-study-ws/src/main/scala/sreo/session/SessionsLive.scala",
        "startLine": 113,
        "endLine": 120
      },
      {
        "id": "sreo/session/SessionLive#closeSession().",
        "displayName": "closeSession",
        "file": "srs-study-ws/src/main/scala/sreo/session/SessionsLive.scala",
        "startLine": 92,
        "endLine": 98
      }
    ]
  ]
}
```

Если путь не найден:

```json
{
  "found": false,
  "from": "...",
  "to": "..."
}
```

Если вершина не существует — SBT выведет ошибку в stderr.

---

## FQN-формат вершин

Плагин использует SemanticDB symbol format:

| Элемент         | Разделитель | Пример          |
|-----------------|-------------|-----------------|
| Пакет           | `/`         | `sreo/session/` |
| Объект (object) | `.`         | `SessionLive.`  |
| Класс/трейт     | `#`         | `SessionLive#`  |
| Метод           | `().`       | `close().`      |

Полный пример: `sreo/session/SessionLive#close().`

**Как узнать точный FQN метода:**

1. Запустить `graphIndex` — убедиться что граф загружен
2. Запустить `graphVia` с приближённым именем и посмотреть соседей
3. Или поискать в JSON-файле: `grep "displayName.*methodName" target/graph-last-result.json`

**Особенности:**

- `val`-поля трейтов/классов тоже попадают в граф как методы (SemanticDB представляет их так же)
- `endLine == startLine` для однострочных определений (полей, абстрактных методов)
- `startLine` и `endLine` — 1-based (человекочитаемые номера строк)

---

## Кэш

Граф загружается при первом вызове любой задачи и кэшируется в памяти SBT-демона. После `compile` кэш инвалидируется автоматически — по mtime файлов в `target/scala-2.13/meta/`.

Принудительно сбросить кэш: перезапустить `sbtn` (редко нужно).

---

## Результат в файле

Все команды пишут JSON в `<module>/target/graph-last-result.json` и печатают путь к файлу в stdout. Это позволяет LLM читать файл напрямую через `Read`.

```
sbtn "studyWs/graphVia sreo/session/SessionLive#close()."
# → /path/to/blank-slate-server/srs-study-ws/target/graph-last-result.json
```
