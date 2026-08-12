# Brief: build a todo-backend-conformant web app around todomvc.sou

Build a web app in this module (`todomvc/`) that implements the
[todo-backend](https://www.todobackend.com/) API contract — its spec source is
https://raw.githubusercontent.com/TodoBackend/todo-backend-js-spec/master/js/specs.js — against the
domain compiled from `src/main/souther/todomvc.sou` and the table in
`src/main/resources/schema.sql`. `pom.xml` already fixes the boundary's stack: Spring Boot, jOOQ, H2.

Rules:

- Don't edit `todomvc.sou`, `TodomvcTest.java`, or `pom.xml`. Read `todomvc.sou` yourself for the
  types and the injected behaviors — every `behavior` with no `let` is a persistence operation you
  implement against `schema.sql`; the ones with a `let` are pure and already proven by their
  `example` rows — reuse them if you build a UI, don't reimplement their logic.
- If the todo-backend contract needs something the current domain doesn't have, that's a modeling
  gap: say so and stop, rather than working around it in the web layer.
- Mount the todo-backend root resource at `/todos`.
- Read `docs/java-boundary-conventions.md` before writing the controller and the injected-behavior
  implementations — it covers how to switch over a generated type and how to validate a boundary
  value, and following it from the start is cheaper than fixing it after.
- This repository ships its own MCP server (`souther mcp`, registered in `.mcp.json`) — use it
  instead of reading another module in this repository (`ordering/`, `realworld/`, ...) for how to
  wire this up. `doc_search`/`doc_read` answer the Souther language questions (what `constructs`
  means, how a `decoder`/`encoder` work, and so on). `jar_api` reads a
  dependency's real public API, with javadoc, straight off this module's own resolved classpath —
  get that classpath once with `mvn -pl todomvc dependency:build-classpath
  -Dmdep.outputFile=/tmp/todomvc-cp.txt` and pass its contents as `jar_api`'s `classpath` argument
  when you need jOOQ's `DSLContext` or Spring's CORS types. This exercise is about building
  correctly from the model and the real APIs, not from a worked example of the same thing.

Done when `mvn -pl todomvc -am clean verify` still passes and todo-backend-js-spec's test suite
passes against the running app.
