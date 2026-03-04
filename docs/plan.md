# Development Plan: sbt-graph-explorer

**Date:** 2026-03-04

Практические шаги реализации в порядке выполнения.

---

## Шаг 0 — Phase 0: разведка SemanticDB (prerequisite)

**Цель:** убедиться что из `.semanticdb` файлов можно извлечь method-level вызовы.

- [ ] Написать минимальный `Main.scala` в `analyzer/`: читает один `.semanticdb` файл, печатает все символы и вхождения
- [ ] Запустить на `blank-slate-server/srs-study-ws/target/scala-2.13/meta/`
- [ ] Убедиться что `Term.Apply` → `fun.symbol` даёт FQN реального метода
- [ ] Записать: формат FQN вершин, нумерация строк (0 или 1), покрытие (implicit вызовы, for-comprehension и т.д.)

**Done when:** для известной пары методов (caller → callee) ребро присутствует в выводе.

---

## Шаг 1 — GraphLoader

**Файл:** `modules/analyzer/src/main/scala/GraphLoader.scala`

- [ ] Рекурсивно найти все `*.semanticdb` в заданной директории
- [ ] Для каждого: загрузить `SemanticDocument`, обойти AST
- [ ] Извлечь `(callerFQN, calleeFQN)` пары из `Term.Apply`
- [ ] Построить `out: Map[String, Set[String]]` и `in: Map[String, Set[String]]`
- [ ] Параллельно собрать `meta: Map[String, NodeMeta]` (file, startLine, displayName)
- [ ] Warning если `out` пуст после загрузки

**Юнит-тест:** `GraphLoaderSpec` — загрузить fixture `.semanticdb` из `src/test/resources/`, проверить граф.

---

## Шаг 2 — QueryEngine

**Файл:** `modules/analyzer/src/main/scala/QueryEngine.scala`

- [ ] `pathAtoB(graph, from, to, maxDepth, maxPaths)` — BFS с `visited: Set[String]`, early-exit по `maxPaths`; возвращает `(Seq[Path], truncated: Boolean)`
- [ ] `viaVertex(graph, v)` — возвращает `(in(v), out(v))` напрямую из map

**Юнит-тест:** `QueryEngineSpec` — рукописный граф, проверить пути, циклы, truncated.

---

## Шаг 3 — CallGraphState

**Файл:** `modules/analyzer/src/main/scala/CallGraphState.scala`

- [ ] `@volatile var cached: Option[(Long, LoadedGraph)]`
- [ ] `getOrLoad(dir: File): LoadedGraph` с mtime-инвалидацией
- [ ] `synchronized` на запись

---

## Шаг 4 — JsonOutput

**Файл:** `modules/analyzer/src/main/scala/JsonOutput.scala`

- [ ] Сериализовать `Seq[Path]` + флаги в JSON
- [ ] Записать в `target/graph-last-result.json`
- [ ] Формат ответа: `{ found, truncated, paths: [[{id, displayName, file, startLine}]] }`

---

## Шаг 5 — Main (CLI для разведки и standalone запуска)

**Файл:** `modules/analyzer/src/main/scala/Main.scala`

- [ ] Аргументы: `<semanticdb-dir> [graphPath A B] [graphVia V] [--maxDepth N] [--maxPaths N]`
- [ ] Без аргументов — печатает статистику: кол-во вершин, рёбер, топ-10 по out-degree
- [ ] С аргументами — прогоняет запрос, пишет JSON в stdout (для standalone удобнее чем файл)

---

## Шаг 6 — SBT Plugin

**Файл:** `modules/plugin/src/main/scala/GraphExplorerPlugin.scala`

- [ ] `AutoPlugin` с `trigger = noTrigger` (подключается явно)
- [ ] Task `graphPath`: парсит аргументы через `sbt.complete.DefaultParsers.spaceDelimited`, вызывает `CallGraphState.getOrLoad` + `QueryEngine.pathAtoB`, пишет файл через `JsonOutput`; печатает путь к файлу
- [ ] Task `graphVia`: аналогично для `viaVertex`
- [ ] Task `graphIndex`: печатает диагностику кэша (вершины, рёбра, timestamp)
- [ ] Путь к `.semanticdb` директории — берётся из `(Compile / semanticdbTargetRoot).value` или хардкодим `target/scala-*/meta`

---

## Шаг 7 — Scripted test

**Директория:** `modules/plugin/src/sbt-test/graph-explorer/basic/`

- [ ] Минимальный тестовый проект с двумя классами (A вызывает B)
- [ ] `test` скрипт: `> compile`, `> graphPath com.example.A#foo com.example.B#bar`, `$ exists target/graph-last-result.json`
- [ ] Запуск: `sbt scripted`

---

## Шаг 8 — Подключение к blank-slate-server

- [ ] `sbt publishLocal` в `sbt-graph-exporter/`
- [ ] Добавить в `blank-slate-server/project/plugins.sbt`
- [ ] Проверить `sbtn "graphPath ..."` на реальном коде

---

## Текущий статус

| Шаг | Статус |
|-----|--------|
| 0 — Phase 0 разведка | ✅ done |
| 1 — GraphLoader | ✅ done |
| 2 — QueryEngine | ✅ done |
| 3 — CallGraphState | ✅ done |
| 4 — JsonOutput | ✅ done |
| 5 — Main CLI | ✅ done |
| 6 — SBT Plugin | pending |
| 7 — Scripted test | pending |
| 8 — Подключение к blank-slate-server | pending |
