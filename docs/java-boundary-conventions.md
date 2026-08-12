# Java boundary conventions for generated Souther types

This applies to any hand-written Java in this repository that calls generated Souther code —
`ordering`, `realworld`, `todomvc`, and any module added later.

## Switches over a generated type are already exhaustive

Every Souther `|`-union (a behavior's output cases, an error union like `Todo | TodoNotFound`) and
every `Result` type (`souther.runtime.Result<T,E>` from `__construct`, `net.unit8.raoh.Result<T>` from
a `decoder()`) compiles to a **sealed** interface — `permits` exactly its own cases, checkable with
`javap -v <ClassName>` if in doubt (look for `PermittedSubclasses`). A `switch` over one is exhaustive
without a `default` arm. If you're reaching for one, the compiler already knows there's nothing left
to match — delete it and let an actually missing case fail to compile instead.

## Match with a record pattern, not `instanceof` and a cast

```java
// not this
if (result instanceof Result.Ok<T, InvariantFailure> ok) {
    return ok.value();
}
Result.Err<T, InvariantFailure> err = (Result.Err<T, InvariantFailure>) result;
throw new ResponseStatusException(HttpStatus.BAD_REQUEST, err.error().toString());

// this
return switch (result) {
    case Ok<T>(var value) -> value;
    case Err<T>(var issues) -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, issues.toString());
};
```

See `CheckoutController`/`FulfilmentController` in `ordering` for the idiom used throughout this
repo.

## Validate a boundary value through its `.decoder()`, not `__construct`

`__construct` (or a `constructs` factory method) is for a value the boundary already trusts — an id it
minted itself, a value built from other already-validated domain values — see `RecordedOrder(...)`
built directly from values already in hand in `JooqRecordOrder`.

A value a caller sent — a request body field, a path variable, a map key — goes through the
generated `.decoder()` instead, not a hand-written type check followed by `__construct`:

```java
// not this
if (!(body.get("title") instanceof String s)) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must be a string");
}
Title title = switch (Title.__construct(s)) {
    case Result.Ok<Title, InvariantFailure> ok -> ok.value();
    default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid title");
};

// this
Title title = decode(Title.decoder().decode(body.get("title")));
```

`decoder()` already does both the type check and the invariant check, and reports a
`net.unit8.raoh.Issue` (which field, which rule, what message) rather than a string composed on the
spot. See `Location` decoded per shelf-map entry in `FulfilmentController`.
