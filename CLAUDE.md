# CLAUDE.md — sbt-graph-explorer

SBT-плагин + standalone analyzer для построения и обхода call graph Scala-проекта через SemanticDB.

---

## Project Structure

```
sbt-graph-exporter/
  build.sbt                          ← корневой build (2 модуля: analyzer, plugin)
  project/
    build.properties                 ← sbtn 1.10.7
    plugins.sbt                      ← sbt-scalafmt
  modules/
    analyzer/                        ← standalone Scala 2.13 app (ядро)
      src/main/scala/me/peter/graphexplorer/
        model.scala                  ← NodeMeta, LoadedGraph
        GraphLoader.scala            ← SemanticDB → (out, in, meta) maps
        QueryEngine.scala            ← BFS pathAtoB / viaVertex
        CallGraphState.scala         ← @volatile var + mtime-инвалидация
        JsonOutput.scala             ← сериализация результата в JSON
        Main.scala                   ← CLI: stats / path / via
      src/test/scala/                ← юнит-тесты (MUnit/ScalaTest)
    plugin/                          ← SBT-плагин Scala 2.12 (pending)
      src/main/scala/
        GraphExplorerPlugin.scala    ← AutoPlugin с тасками graphPath/graphVia/graphIndex
      src/sbt-test/
        graph-explorer/basic/        ← scripted test
  docs/
    spec.md                          ← требования и архитектура
    plan.md                          ← план реализации с чекбоксами
```

---

## Build & Run

**Scala версии:** `analyzer` — Scala 2.13.18; `plugin` — Scala 2.12.20 (требование SBT).

Целевой проект: `blank-slate-server`, модуль `srs-study-ws`.
SemanticDB директория: `/Users/b_petr/IdeaProjects/blank-slate/blank-slate-server/srs-study-ws/target/scala-2.13/meta`

```sh
# компиляция всего
sbtn compile

# stats — кол-во вершин/рёбер, топ калеры:
sbtn "analyzer/run /Users/b_petr/IdeaProjects/blank-slate/blank-slate-server/srs-study-ws/target/scala-2.13/meta"

# найти вызывающих/вызываемых конкретного метода:
sbtn "analyzer/run /Users/b_petr/IdeaProjects/blank-slate/blank-slate-server/srs-study-ws/target/scala-2.13/meta via sreo/session/SessionLive#close()."

# найти путь между двумя методами:
sbtn "analyzer/run /Users/b_petr/IdeaProjects/blank-slate/blank-slate-server/srs-study-ws/target/scala-2.13/meta path sreo/session/SessionLive#close(). sreo/study/StudyServiceLive#submit()."

# тесты analyzer:
sbtn "analyzer/test"

# публикация плагина локально:
sbtn publishLocal
```

**FQN-формат** (SemanticDB): `sreo/session/SessionLive#close().` — пакет через `/`, класс через `#`, метод с `().` на конце.

---

## Key Design Decisions

- **SemanticDB как источник данных** — `.semanticdb` генерируются при `compile` через `semanticdb-scalac` (уже включён через sbt-scalafix в целевом проекте). Никаких дополнительных плагинов.
- **Извлечение рёбер** — через `SymbolOccurrence.Role.REFERENCE` на `Kind.METHOD` в `.semanticdb` (без AST-обхода `Term.Apply`). Caller определяется как ближайший method definition выше по строке.
- **FQN вершин** — SemanticDB-формат, например: `me/peter/graphexplorer/GraphLoader.load(+1).`
- **startLine** — 0-based (как в SemanticDB protobuf).
- **endLine** — парсится отдельно из `.scala` исходника через scalameta; `None` если источник недоступен.
- **Кэш** — `@volatile var` + `synchronized` на запись + mtime-инвалидация по директории `.semanticdb`.
- **Вывод** — пишет JSON в `target/graph-last-result.json`; в stdout печатается путь к файлу (надёжнее чем stdout в SBT из-за `[info]`-префиксов).
- **`analyzer` и `plugin` не связаны через `dependsOn`** — несовместимые версии Scala (2.13 vs 2.12). Взаимодействие через внешний процесс или jar-вызов — решается при реализации плагина (Шаг 6).

---

## Implementation Status

| Шаг | Что | Статус |
|-----|-----|--------|
| 0 | Phase 0 — разведка SemanticDB | ✅ done |
| 1 | GraphLoader | ✅ done |
| 2 | QueryEngine | ✅ done |
| 3 | CallGraphState | ✅ done |
| 4 | JsonOutput | ✅ done |
| 5 | Main CLI | ✅ done |
| 6 | SBT Plugin (GraphExplorerPlugin) | ⬜ pending |
| 7 | Scripted test | ⬜ pending |
| 8 | Подключение к blank-slate-server | ⬜ pending |

---

## JSON Output Format

```json
{
  "found": true,
  "truncated": false,
  "paths": [
    [
      { "id": "...", "displayName": "bar", "file": "src/.../Foo.scala", "startLine": 42, "endLine": 55 },
      { "id": "...", "displayName": "qux", "file": "src/.../Baz.scala", "startLine": 88, "endLine": 101 }
    ]
  ]
}
```

Ошибки:
```json
{ "error": "vertex not found", "query": "..." }
{ "found": false, "from": "A", "to": "B" }
```

---

## Open Questions

1. Как передавать FQN из SBT-плагина в `analyzer` (Scala 2.12 vs 2.13): subprocess `java -jar`, `sbt-assembly`, или скопировать source в plugin?
2. Нужен ли `graphSearch keyword` для поиска по частичному имени?
3. Покрытие implicit/for-comprehension вызовов — проверить на реальном `blank-slate-server`.
