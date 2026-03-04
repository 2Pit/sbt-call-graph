# Task: sbt-graph-explorer

**Date:** 2026-03-03
**Status:** spec (phases 1–6 complete)

---

## Goal

Дать LLM (и разработчику) возможность находить пути вызовов между методами Scala-проекта через `sbtn`-команды, чтобы читать только релевантный код вместо загрузки всего файла.

## Users

- **Primary:** LLM (Claude Code) — вызывает `sbtn` задачи, получает JSON, читает только нужные методы
- **Secondary:** разработчик — использует те же команды в консоли

---

## Acceptance Criteria

- [ ] `sbtn "graphPath A B"` возвращает валидный JSON с путём от A до B (или `{"found": false}`)
- [ ] `sbtn "graphVia V"` возвращает пути, проходящие через вершину V
- [ ] JSON-ответ содержит для каждого метода: полное имя (`id`), файл, номер строки
- [ ] Первый вызов после компиляции завершается < 10 сек (индексация)
- [ ] Повторный вызов (кэш) завершается < 500 мс
- [ ] После `compile` кэш инвалидируется автоматически (по mtime `.semanticgraphs/`)
- [ ] Запрос с несуществующей вершиной возвращает `{"error": "vertex not found", "query": "..."}`, не stack trace
- [ ] Если пути нет — `{"found": false, "from": "A", "to": "B"}`
- [ ] `.semanticgraphs/` отсутствует → сообщение "Run `compile` first"
- [ ] `maxDepth=20`, `maxPaths=100` по умолчанию; конфигурируемы через параметры задачи
- [ ] При достижении лимита — `"truncated": true` в ответе
- [ ] Циклы не вызывают бесконечный поиск (simple paths only)
- [ ] Параллельные `sbtn`-вызовы не ломают кэш
- [ ] `sbtn "graphIndex"` возвращает диагностику: кол-во вершин/рёбер, timestamp кэша

## Out of Scope

- HTTP-сервер / REST API
- Визуализация (DOT / Mermaid) — отдельный этап после MVP
- Инкрементальная индексация
- Поддержка нескольких графов одновременно
- UI/TUI для человека
- Метрики графа (fan-in, centrality и т.д.)
- Поиск по частичному имени (только точный FQN)

## Backlog (v2)

- Аннотации/теги на вершинах (`@api`, `@db`, `@rest`) — задаются вручную
- `transparent`-вершины — исключаются из путей (прозрачные транзитные узлы)
- Фильтрация по cardinality (автоматически убирать "шумные" хабы)
- Визуализация: JSON → DOT, JSON → Mermaid

## Open Questions

1. Точный строковый литерал edge type для вызовов (`"calls"`? `"CALL"`?) — выяснится при первом `graphIndex` на реальном проекте
2. Содержит ли protobuf `startLine` нумерацию с 0 или с 1? (влияет на offset для `Read`)
3. Нужен ли `sbtn "graphSearch keyword"` — поиск вершины по частичному имени? Без него Claude должен знать точный FQN заранее

---

## Architecture

### Stack

| Слой                 | Инструмент                                                                                   |
|----------------------|----------------------------------------------------------------------------------------------|
| Семантический анализ | Scala compiler + `semanticdb-scalac` (уже включён через sbt-scalafix)                       |
| Артефакты            | `target/scala-2.13/meta/**/*.semanticdb` — генерируются при каждом `compile`                |
| Чтение артефактов    | `scalameta` — читает `.semanticdb` + исходник, даёт `SemanticDocument`                      |
| Извлечение вызовов   | Обход AST: `Term.Apply` → `fun.symbol` → FQN; `enclosingMethod` → caller FQN               |
| In-memory граф       | `Map[String, Set[String]]` (out) + `Map[String, Set[String]]` (in) + `Map[String, NodeMeta]`|
| Поиск путей          | Собственный BFS с visited set + depth/paths счётчиком                                        |
| JSON                 | upickle или ручная сериализация                                                              |
| Вывод результата     | `target/graph-last-result.json` (путь печатается в stdout)                                  |
| Интерфейс            | SBT tasks, вызов через `sbtn`                                                                |

### Как SemanticDB даёт call graph

`.semanticdb` файлы содержат для каждого исходника: символьные определения + все вхождения символов с позициями. Через scalameta API:

```scala
// для каждого .semanticdb + соответствующего .scala файла:
val doc = SemanticDocument.fromPath(...)
doc.tree.collect {
  case apply: Term.Apply =>
    val callee = apply.fun.symbol  // FQN вызываемого метода
    val caller = apply.enclosingMethod // FQN вызывающего метода
    caller → callee                // ребро графа
}
```

Никаких дополнительных плагинов не нужно — scalafix уже включает генерацию `.semanticdb`.

### Состояние между вызовами

```scala
object CallGraphState {
  @volatile private var cached: Option[(Long, LoadedGraph)] = None

  def getOrLoad(semanticGraphsDir: File): LoadedGraph = {
    val mtime = lastModified(semanticGraphsDir)
    cached match {
      case Some((ts, g)) if ts == mtime => g // cache hit
      case _ =>
        val g = GraphLoader.load(semanticGraphsDir)
        cached = Some((mtime, g))
        g
    }
  }
}
```

`LoadedGraph` = `(out: Map[String, Set[String]], in: Map[String, Set[String]], meta: Map[String, NodeMeta])`
где `NodeMeta` = `(file: String, startLine: Int, displayName: String)`

### Структура модуля

```
sbt-graph-explorer/
  build.sbt
  project/build.properties
  modules/
    analyzer/                      ← ядро, standalone Scala app
      src/main/scala/
        GraphLoader.scala          ← SemanticDB → (out, in, meta) maps через scalameta AST
        QueryEngine.scala          ← pathAtoB (BFS) / viaVertex (in/out lookup)
        JsonOutput.scala           ← сериализация результата в JSON
        CallGraphState.scala       ← @volatile var + mtime-инвалидация
        Main.scala                 ← CLI: принимает путь к target/scala-*/meta/, печатает статистику / проверяет пути
      src/test/scala/
        QueryEngineSpec.scala      ← юнит-тесты BFS на рукописном графе
        GraphLoaderSpec.scala      ← тест парсинга SemanticDB из test fixtures (.semanticdb + .scala)
    plugin/                        ← SBT-плагин, зависит от analyzer
      src/main/scala/
        GraphExplorerPlugin.scala  ← AutoPlugin, регистрирует SBT tasks, делегирует в analyzer
      src/sbt-test/
        graph-explorer/basic/      ← scripted test: compile → graphPath → проверка файла
```

### Стратегия тестирования

| Уровень | Инструмент | Что проверяет |
|---------|-----------|---------------|
| Юнит | ScalaTest / MUnit в `analyzer` | BFS логика, JSON-формат — без SBT и без реальных артефактов |
| Интеграция | `analyzer/run` на реальном проекте | Чтение SemanticDB, корректность извлечения method-level вызовов (Phase 0) |
| SBT | scripted tests в `plugin` | `sbtn` задачи работают end-to-end, файл с результатом создаётся |

**Запуск analyzer standalone:**
```
sbt "analyzer/run /path/to/project/.semanticgraphs"
# → печатает: nodes: 1823, edges: 4201, edge types: {calls: 3800, references: 401}, ...
# → можно добавить: --check "com.example.Foo#bar -> com.example.Baz#qux"
```

---

## Technical Design

**Affected modules:** новый отдельный SBT-плагин (отдельный репозиторий или подмодуль)

**Data model changes:** нет

**API changes — новые SBT-таски:**

```
sbtn "graphPath com.example.Foo#bar com.example.Baz#qux"
sbtn "graphPath A B --maxDepth 15 --maxPaths 50"
sbtn "graphVia com.example.Service#process"
sbtn "graphIndex"
```

**JSON-ответ (graphPath / graphVia):**

```json
{
  "found": true,
  "truncated": false,
  "paths": [
    [
      {
        "id": "com.example.Foo#bar",
        "displayName": "bar",
        "file": "src/.../Foo.scala",
        "startLine": 42
      },
      {
        "id": "com.example.Baz#qux",
        "displayName": "qux",
        "file": "src/.../Baz.scala",
        "startLine": 88
      }
    ]
  ]
}
```

**Implementation steps:**

1. Создать SBT-плагин проект, добавить зависимости: `scalameta`, `semanticdb`, `upickle` (JSON)
2. Реализовать `GraphLoader`: находит все `.semanticdb` файлы в `target/scala-*/meta/`, для каждого загружает `SemanticDocument` (semanticdb + исходник), обходит AST → `Term.Apply` → извлекает caller/callee FQN, строит `(out, in, meta)` maps; если `out` пуст — warning с примерами найденных символов
3. Реализовать `CallGraphState`: `@volatile var` + `synchronized` на запись, mtime-инвалидация
4. Реализовать `QueryEngine.pathAtoB`: BFS с `visited: Set[String]` (цикло-защита), `depthLimit`, early-exit по `maxPaths`; возвращает `(paths, truncated)`
5. Реализовать `QueryEngine.viaVertex`: `in(V)` + `out(V)` — прямая выборка из map, без поиска путей
6. Реализовать `JsonOutput`: сериализует результат → пишет в `target/graph-last-result.json`; печатает путь к файлу в stdout
7. Зарегистрировать таски в `GraphExplorerPlugin extends AutoPlugin`
8. **Phase 0 (prerequisite):** подключить scg-cli, сгенерировать граф, исследовать в Gephi — убедиться что method-level call edges присутствуют и определить точный edge type
9. Подключить плагин к `blank-slate-server/project/plugins.sbt`, запустить `compile`, проверить

**Migration / rollout:**

- Добавить в `project/plugins.sbt` blank-slate-server
- Первый `compile` генерирует `.semanticgraphs/` (scg-cli уже должен быть подключён)
- Без миграций данных, без feature flags

---

## Edge Cases & Risks

| Risk                                                                                                                                                        | Severity | Mitigation                                                                                                                |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------|----------|---------------------------------------------------------------------------------------------------------------------------|
| Risk | Severity | Mitigation |
|------|----------|------------|
| **SemanticDB не содержит нужных вызовов** — `Term.Apply` не покрывает все формы вызовов (implicit, eta-expansion, SAM) | high | **Phase 0 — hard prerequisite:** запустить analyzer на `blank-slate-server`, проверить что известные цепочки вызовов присутствуют в графе |
| **Граф пуст или неполный** — `enclosingMethod` не резолвится, или `fun.symbol` пуст для некоторых Apply | high | После загрузки: если `out` пуст → warning с примерами необработанных узлов |
| **Циклы (рекурсия, mutual recursion)** | high | BFS с `visited: Set[String]` — уже включено в дизайн |
| **Protobuf-файл повреждён / записан частично** (compile не завершён) | med | `try/catch` на парсинг каждого файла; пропускать битые с warning |
| **Парсинг аргументов задачи** — SBT `inputKey` требует `Parser`-комбинаторов | med | `sbt.complete.DefaultParsers.spaceDelimited` + ручной split из `Seq[String]` |
| **scalameta `SemanticDocument` требует исходник рядом с `.semanticdb`** — если исходники перемещены или структура нестандартная | med | Проверить в Phase 0; scalameta умеет резолвить пути через стандартную структуру SBT |
| **startLine нумерация 0 vs 1** | low | Верифицировать на известном файле в Phase 0 |
| **Race condition при инвалидации кэша** | low | `synchronized` на запись `cached`; двойная работа не ломает корректность |
| **ScalaPB codegen** — `.proto`-схема VirtusLab может измениться | low | Вендорить `.proto` в репозиторий; обновлять вручную |
| **SBT-демон рестартует** (build.sbt изменился) | low | Кэш сбрасывается; первый вызов переиндексирует автоматически |

**Backlog v2:** при загрузке графа проверять плотность (`edges / nodes`) и писать warning в stderr если аномально высокая.

---

## Trade-offs

- **`var` вместо SBT `AttributeKey`** — нестандартно, но значительно проще. `AttributeKey` требует threading state через все таски. Для изолированного плагина риск минимален.
- **Только точный FQN** — упрощает реализацию; недостаток: Claude должен знать точное имя заранее. Частичный поиск — в backlog.
- **Граф только из `"calls"` рёбер** — игнорируем `extends`, `implements`, `references`. Можно расширить в v2.
- **Собственный BFS вместо JGraphT** — ноль зависимостей, полный контроль над early-exit и ограничениями. Достаточно для разреженного проекта. Если понадобятся сложные алгоритмы (centrality, isomorphism) — JGraphT можно добавить позже.
- **SemanticDB вместо scg-cli** — не требует внешнего плагина, работает с любой версией Scala 2.13, уже включён через scalafix. Минус: нужно реализовать AST-обход самому; scg-cli давал готовые call edges.
- **Вывод в файл, не в stdout** — надёжнее для LLM (stdout в SBT загрязнён `[info]`-префиксами и ANSI-кодами). Небольшой overhead на запись файла незначителен.

---

## Self-Critique

1. **Главное допущение — scg-cli строит method-level call graph.** Это не подтверждено. Phase 0 — не исследовательский бонус, а блокер. Если граф только class-level, весь дизайн переписывается.

2. **JGraphT убран обоснованно.** Для разреженного проекта BFS на `Map` достаточен. Если выяснится обратное — добавить JGraphT несложно.

3. **Самый простой альтернативный подход** — экспортировать полный граф в JSON и дать Claude читать его напрямую. Отклонён: полный граф большого проекта слишком велик для контекста LLM, что противоречит цели.

4. **Stdout vs файл** — изменено в финальном дизайне. Файл надёжнее для machine-readable вывода в SBT.