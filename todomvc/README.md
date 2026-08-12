# TodoMVC, with only the data and the invariant in Souther

TodoMVC has almost no domain logic — that is the point of picking it. What a todo app needs written
down somewhere is the shape of a Todo, the one invariant a title must hold, and which todos a filter
shows. Everything else — storing a row, reading rows back, flipping `completed`, deleting a row — is
CRUD with no rule behind it, the kind of thing a Rails scaffold produces without anybody writing a
line of it by hand.

This module states the first part in `src/main/souther/todomvc.sou` and stops there. The persistence
and HTTP layer that a scaffold would generate is deliberately not written by hand here; `PROMPT.md` is
the brief for a coding agent to build it instead, against `schema.sql` and the compiled contract
`todomvc.sou` already produced. The API to build is not invented — it's
[todo-backend](https://www.todobackend.com/)'s contract, so any conforming implementation can be
checked against its published test suite and pointed at from its (or any) TodoMVC-style frontend.

## What's here

- `src/main/souther/todomvc.sou` — `Todo`, `Title`, `Position`, `Filter`, and the two pure rules
  (`visibleTodos`, `remainingCount`), each with `example` rows the compiler checks on every build.
  Every read/write behavior (`storeTodo`, `readTodos`, `findTodo`, `renameTodo`, `setCompleted`,
  `repositionTodo`, `removeTodo`, `deleteAllTodos`) is declared with no `let` — injected, on purpose.
  `Filter`/`visibleTodos`/`remainingCount` aren't part of the todo-backend contract itself; they're
  there for whatever frontend ends up pointed at the finished app.
- `src/main/resources/schema.sql` — the `todos` table those injected behaviors read and write.
- `src/test/java/example/todomvc/TodomvcTest.java` — drives the two pure rules from the Java
  boundary, the same way `whodunit`'s test drives `solve`.
- `PROMPT.md` — the brief handed to a coding agent to build everything outside `todomvc.sou`.

## Checking what's here

```sh
mvn -pl todomvc -am clean verify
```

This compiles `todomvc.sou` (so a broken `example` row fails the build) and runs `TodomvcTest`. It
does not start anything — there's no web app yet, and this module isn't wired to run one.

## Building the outside

Hand a coding agent `PROMPT.md`. It has everything it needs: the generated types and injected
behavior interfaces todomvc.sou already produced, the table they read and write, and the two rules it
must reuse rather than re-derive.
