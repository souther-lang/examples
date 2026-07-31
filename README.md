# Souther examples

Examples for [Souther](https://github.com/souther-lang/souther), kept in their own repository
because they move on the compiler's schedule for the language and on Spring / jOOQ / Kotlin's
schedule for the boundary code, and those two are not the same.

They are MIT-licensed, rather than EPL-2.0 like the compiler, so that a project can copy what it
needs from here without taking anything on.

These examples exercise the whole Souther development lifecycle — write `.sou`, generate, use the
generated types from typed code, compile and test. Each business unit is an independent Maven
module: it generates types from its `.sou`, uses them from typed code, and runs a smoke test over
decode/encode.

A domain definition is just **data + invariant + behavior**. Decoders and encoders are not part of
the Souther notation; they are **derived** from the data shape (JSON key = field name; a data with a
single primitive field is a newtype = the bare primitive; the discriminator field of a sum is
`"type"` and the tag is the case name).

## How generation works: a javac annotation processor

`.sou → .class` is done not by a dedicated build-tool plugin but by a **javac annotation processor**
(`souther.compiler.apt.SoutherProcessor`). Whenever `mvn compile` (or plain javac, or
Gradle) runs, the processor compiles the `.sou` files in `src/main/souther` and emits the generated
types into `target/classes`. Because `target/classes` is on javac's compile classpath, the
hand-written code (and the smoke tests) **compile directly against those generated types**. No exec
step, no separate module, no Souther-specific plugin.

The whole Maven wiring is just this (set once for all modules in the root `pom.xml`):

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>org.souther-lang:souther-compiler:0.1.0-SNAPSHOT</path>
    </annotationProcessorPaths>
    <compilerArgs><arg>-Asouther.source=${project.basedir}/src/main/souther</arg></compilerArgs>
  </configuration>
</plugin>
```

`souther-compiler` only sits on `annotationProcessorPaths`; it is not an app dependency and does not
end up in the artifact jar. With Gradle you use the same processor via an `annotationProcessor`
dependency plus the `-Asouther.source` compiler arg.

Importing a module another project compiled needs nothing beyond the dependency itself. `sharedmoney`
publishes `shared.money`; `invoicing` depends on that jar, has only its own `.sou` under
`src/main/souther`, and writes `import shared.money ( Amount )`. The processor reads what the module
declared off the classes on the compile classpath, which is what depending on a jar already puts
there, and `invoicing`'s output holds no `shared/money` class — the dependency's classes are its own
build's.

Both of those projects carry a `package-info.java` they would rather not need: an annotation
processor runs as part of compiling Java, and javac does nothing at all when a project has no Java
source. A project written only in Souther has to write one file in another language to be built.

A compile error is reported the way the CLI reports it — the title, the position, the offending line
with a caret, and the hint. `-Asouther.lang=en` picks the language of the message; without it the
processor follows `SOUTHER_LANG` and then the JVM's default locale, as `souther --lang` does.

## Modules

| Module | What it shows |
| --- | --- |
| `email` | A single-field data + invariant (the minimal example: a newtype decoded from a bare string) |
| `expense` | `List<T>` / nested newtypes / a product decode·encode round trip |
| `cart` | List combinators `map`/`filter`/`all`/`any` (`souther.list` derives them from `fold`) + the empty list `[]`. Actually runs the behavior `quote` and checks its result cases |
| `businesstrip` | A business trip application, written 1:1 from a specification DSL. **The states of the application are the sum**: the nine cases of `出張申請` each carry only what that state has (`提出済み` has a submission time, `承認完了` has a settlement amount), and a behavior's input is a state type — so approving an application nobody submitted is not a call you can write. Field composition is the `...出張申請共通項目` spread, layered twice (`出張完了共通項目` spreads it again and the completed states spread that). Sums nest — `費用負担区分`'s case `自社負担` is itself the sum `立替 \| 仮払い \| 会社カード` — and the rule "only an out-of-pocket 立替 line is reimbursed" is exactly the two-stage `match` that opens one level at a time. The derived codec folds the nesting the other way, dispatching over the leaves, so the JSON stays one flat `{"type": "立替"}`. The expense categories hold different fields per case, so a total pulls each amount out through a `match` before `List.sum`. Preconditions are outcomes rather than aborts: an empty rejection reason returns the `却下理由なし` case, and the newtype `却下理由` is built after the `guard` that discharges its invariant. Where the DSL writes `事前承認日時? AND 事前承認者ID?`, the model uses a sum (`事前承認なし \| 事前承認あり`) so that a timestamp with no approver cannot be represented. The smoke test drives the whole run — decode a draft, submit, pre-approve, complete, request and grant final approval — and checks the settlement is the out-of-pocket lines only |
| `joboffer` | A crowdsourcing job offer: **a sum of sums of sums** (依頼 → プロジェクト依頼 → 精算方式 → 固定精算 → 予算 → 範囲予算), with the value-less cases of an enum as unit data, declared by the sums that name them and nowhere else. Ported from [kawasima/validation-modeling](https://github.com/kawasima/validation-modeling)'s `raoh` version, where the same model is a hand-built decoder carrying the constraints; here the constraints are the newtypes' invariants and the decoder is derived from them. It is the one example that reads a Jackson `JsonNode` through the generated `jsonDecoder()` (which is also how a date arrives as JSON text), and it runs Spring Boot for real — with no database, since both behaviors are pure |
| `issuetracker` | A small issue tracker, and the **Kotlin** case: the boundary around the domain — REST and the H2 connection — is Spring Boot + Kotlin (below). Showcases the `Set` module (an issue's `labels` are a `Set<Label>` — the derived codec dedups a JSON array — and `openIssue` cleans the raw label text with `Set.map` + `Set.filter` without leaving the set for a list first), the `Map` module (`countByLabel` builds a `Map<String, Int>` with `Map.upsert`; `topLabels` ranks those counts and splits the ranked pairs with `List.unzip`; `busyLabels` keeps the entries a threshold holds for with `Map.filter`; `groupByAssignee` buckets the issues themselves into a `Map<String, List<IssueId>>` with `List.filterMap` + `List.groupBy`, the optional assignee dropping out without a stand-in value), `List.concatMap` to gather every label occurrence across the board, `Some(Assignee(name))` destructuring of an optional assignee, and three injected database behaviors whose read → transform → write sequencing is checked with `fake` + `example`. Like ordering it actually starts Boot and connects to H2, and — being the Kotlin case — it is built with Gradle rather than Maven |
| `member` | Member lookup. A `required behavior findMember` (outside-world dependency) + type routing `>->`. Actually compiles the Spring MVC + jOOQ boundary code (below) |
| `account` | Account withdrawal, "read → check → write". Binds `withdraw` (which has two injected behaviors) from **Clojure + Pedestal rather than Java**, connected to H2 inside a transaction (below). It shows that the generated types are used the same way even when the boundary language changes |
| `ordering` | Ordering + stock reservation. Two injected behaviors joined with `>->`, and it **actually starts Spring Boot, connects to H2, and shows transaction control**: if the second stage returns the `OutOfStock` case, the first stage's INSERT is rolled back too (below). Also a pure `report` over a recorded order — a sales summary showcasing `distinct` (the old standalone `sales` example, folded in here) |
| `tax` | Consumption tax, and the **only example that uses `Decimal`**: a fourth Souther module inside the ordering project. Amounts stay `Int` (yen has no fraction) and only the rate is a `Decimal`, so the conversion is visible exactly where a value stops being yen — `Decimal.fromInt(net) * rate.value` — and comes back with the rounding named: `Decimal.toInt(…, FLOOR)`. The rate is also *written down* in the domain: `String.fromDecimal(Decimal.round(…))` turns it into the `"10%"` a qualified invoice states, instead of leaving that to the boundary. The rate itself is injected (`rateOf`), read by jOOQ from the schema's one `NUMERIC` column, and its range is a newtype invariant that runs where it is built — a row outside it aborts rather than entering the domain. Tax is rounded once per rate, not per line; an `example` pins the 1-yen difference the wrong way would produce |
| `inventory` | The warehouse side. A third Souther module living inside the ordering project alongside `cart` and `ordering` (so it `import`s cart's `PricedCart`): `allocate` (read → index → aggregate check → write — read the stock rows, index them by sku with `List.indexBy`, check every line is covered with `all`, then commit), EAN-13 `inspectBarcode` (a check-digit fold with `List.indexedMap` / `List.sum`), whole-case `verifyShipment`, `putAway`, and `baySlots` — which *builds* shelf codes rather than only checking ones that arrive, with `List.range` over the levels of a bay and `String.padLeft` widening each number to the two digits `Location`'s invariant demands |
| `crm` | The CRM core, and the first of the five Souther modules in the CRM and sales-force-automation example. It carries forward what the old `contact` module was the only place to show — a sum decoded and encoded through its `"type"` discriminator, an unknown tag as a decode failure, and a newtype's format invariant enforced when the value is reached *through* the discriminator — now on `ContactPoint`, whose three cases carry payload. **A pair of optionals becomes a sum**: Salesforce's nullable Email plus nullable Phone spells four states and the one that matters, reachable by nothing, is representable; three cases remove it, and `title: JobTitle?` sits three lines below as the optional that is right because no rule depends on it. **The lead's states are the sum**, with the touch record spread onto the common items so only the states past New carry it, and a converted lead cannot be converted again because `convertLead` takes a `QualifiedLead` and produces a `ConvertedLead` — there is no guard to forget. Conversion reports **every** blocking reason rather than the first, which is what building the reasons as a value rather than as a `guard`'s departure buys. Duplicate screening keeps Salesforce's two standard rules with their two different match keys: accounts on the email domain, contacts on the exact address, both checked against books passed in as data. One injected behavior, bound from plain Java in the smoke test with no framework |
| `pipeline` | The SFA core: **the ten Salesforce stages, and the stage is the type**. Every transition takes the exact stage it advances from, so there is no stage argument, no stage check, and no way to write a call that sends a proposal to a deal nobody qualified — `PipelineTest` says so by omission, since the illegal transitions are absent because they do not compile. Six spread layers, because a deal accumulates commitments in six steps and each step is what the next stage needs to exist; that is what makes `withdrawProposal` honest, since pulling a quote returns a state that has no quote number rather than one holding a stale value. The probabilities and forecast categories are the real mappings as ten-arm matches, so adding a stage is a compile error in three places rather than a deal that quietly weighs nothing. `LossReason` nests because win/loss review asks whether somebody else won it or nobody bought, and only the displaced kind names a rival — while the codec folds to the leaf, so the JSON stays one flat tag. The seam with `crm` is two calls with a Java line between them: `crm` builds the account and the contact, `pipeline` builds the opportunity and answers with **its own** `NoOpportunityRequested`, because an output union is built from the cases the module declares |
| `activity` | The activity log. Salesforce's polymorphic `WhatId` — one column holding an account, an opportunity or a lead id — is a sum, which is why this module imports both `crm` and `pipeline`. Three kinds of activity with different fields, so a total over them opens each kind before it can read a date (a meeting's is a `DateTime` that comes down to a `Date` first). `Set` where the question is membership: multi-threading a deal is the best-known loss predictor and two meetings with one person are not two people. `Map.upsert` folding to a **minimum** rather than a sum, because recency is the freshest touch and not the total. `Map<Date, Int>` as an output, keyed by a temporal that crosses as its ISO form. And the next-step playbook — an eight-arm match over the open stages prescribing what that stage's exit criterion needs, which is where the pipeline stops being a report and starts telling somebody what to do on Monday |
| `quoting` | Quote lines and CPQ. **Where `pipeline` makes the states the type, this module makes an attribute the sum**: a quote is the same document before and after approval, so its `ApprovalState` is a four-case sum on a field where Salesforce has a flag and three nullable columns — one of whose cases, approved automatically, has nobody to name. A product invariant across three fields pins `net = quantity × list × (1 − discount)`, and **every ratio rule in the file is written as the multiplication it implies**, because a division inside an invariant aborts on a zero denominator instead of rejecting the value. Both CPQ caps are real: per line, and **blended**, which is the one that holds — a rep who needs thirty per cent off takes it all off one line and every per-line check passes. The injected `approverFor` answers with `crm`'s `UserId`, which the implementation builds through `crm`'s public decoder — the path a value arriving from outside takes |
| `forecasting` | The weighted forecast, and the module that **builds its own map key**: `FiscalPeriod` is `"FY26-Q3"`, assembled from the close date and the org's fiscal-year start month with `Date.year` / `Date.month` / `Int.modBy` / `String.padLeft`, then checked by the same regex a key arriving from outside would face. The weighting is the project's one real division, so the scale and the rounding mode are named at the call. It is also where **`Map.union` is deliberately not used**: a manager's forecast is the reps' forecasts summed, and union is left-biased and never merges values, so it would silently drop a rep's number on a shared period — combining two values under one key is a domain decision, and the fold with `upsert` is where that decision lives. A two-argument injected `quotaFor`, which is the third generated shape after `pipeline`'s zero-argument one and `crm`'s one-argument one. The only module here with no `exposing` clause, sitting beside `quoting`, which writes one although nothing imports it either |
| `employee` | The vocabulary the other seven HR modules speak, and the first of the eight in this repository's largest project. **The person is four data with four reasons to change** — an identity that is only ever corrected, a name that changes with a notification behind it, terms that change when the contract does, and a remuneration that changes at a pay review — and the employment states are a sum spreading all of them. The remuneration is four fields because **four rules add them up four different ways**: a grade is decided from everything paid in the month, an occasional revision is triggered by the fixed part, the short-time coverage test reads the scheduled wage, and an overtime uplift is computed on a base the statute strips family, housing and commuting allowances out of. `attainedAgeOn` is one line and is what a great many payroll systems get wrong: a person attains an age on the day *before* their birthday, so somebody born on the first of a month starts paying long-term-care premiums a month earlier than their birthday suggests. My Number's check digit is a behavior rather than an invariant, since an invariant may not fold — the same division `inventory` draws for EAN-13 |
| `dependents` | Who counts as a dependant, asked twice of the same person and answered differently. **Two measures of one thing, kept apart by their types**: the social-insurance test reads revenue (everything coming in, taxable or not) and the income-tax test reads income (revenue less the employment-income deduction), both wrap `Int`, and neither compares with the other — so the rule that reads revenue cannot be handed income. A spouse earning 1.2 million yen is a dependant for health insurance and is not one for tax, and that pair of answers is the module. Living apart carries the remittance **on the case**, so the support test cannot be written against a person whose remittance nobody recorded. Both judgments report every failing reason rather than the first, built as a value and attempted against the blocked case's own invariant. Which category a dependant falls in is decided here; what the category is worth is `yearendadjustment`'s, because the categories have stood for decades and the amounts moved in 2025 |
| `socialinsurance` | Health and pension insurance, and the module the domain fights back in. **The fifty-row standard-remuneration table is data and the lookup a fold**, which is what a table that size wants — its own rules are on the type (fifty rows, no repeated grade) except the one the lookup actually depends on, since sortedness is a fold and an invariant may not fold (F29). The thirty-two pension grades are the health grades shifted by three and clamped, which is what the statute says they are rather than a second table to keep in step. Coverage is two tests — the three-quarters rule and a four-condition short-time rule asked only of whoever it turns away — answered by two attempted constructions, and the answer is **which of the two insurances apply**, since pension coverage ends at 70 and health at 75 and there is no window where pension applies and health does not. A three-month window is a product of three fields, because three is an arity and not a size. The employee's half of a premium rounds HALF_DOWN because the statute drops 50 sen, and the employer pays the rest — so the halves of a 10,901-yen premium are 5,450 and 5,451 |
| `employmentinsurance` | Unemployment insurance, and **a five-by-five statutory matrix as a nested match**, so adding a band is a compile error in the five places that have to answer for it. Two of the twenty-five cells are blank in the printed table — twenty insured years cannot have accrued before thirty, and an ordinary recipient with under a year is not entitled at all — and exhaustiveness asks for an arm for each with nothing to write that says the combination cannot arise (F30). The 31-day employment expectation is **derived rather than asked for**, read off the contract term, so there is no second field for the same fact to be recorded inconsistently in. The separation reason is read at the *outer* level here: every employer cause makes a specified-eligible recipient and none is restricted, while the dismissal that is the worker's own fault sits outside that group in `employee` for exactly this reason |
| `attendance` | Working hours, and the module that produces **hours and multipliers and never money** — the uplift rates are labour law and an hourly rate is pay policy, so multiplying them is `payroll`'s business. Late-night hours are tallied on their own and counted again rather than a fifth bucket being invented for the overlap, so overtime after ten at night is 50 per cent by addition and holiday work after ten at night is 60, with no arm anywhere that says so. Work on the statutory holiday is holiday work and never overtime, which is why it misses the sixty-hour count. The overtime agreement is judged under two regimes with five reasons, and the four figures the special clause is bounded by are not one measure — two count holiday work and two do not. Annual leave reads a five-by-seven accrual table **whose row is chosen by two fields**, because thirty hours over three long days is the full entitlement and not a proportional one |
| `payroll` | Gross to net, and the module the other four feed into. **Two months in one pay slip**: premiums are withheld a month in arrears, so the premium on a July slip is June's, and `PayMonth` and `ContributionMonth` are two types with the statute as the behavior between them. **Two rounding rules a screen apart**: an uplift settles HALF_UP and a premium HALF_DOWN, because one circular carries 50 sen and the other drops it. A deduction is not an amount but an amount with a basis — wages are paid in full and only what a statute or a labour-management agreement allows may come out — so an agreed deduction carries the agreement it rests on and a line with no basis is not representable. The slip's arithmetic is an invariant on the type, which is also why the fold behind it answers a bare `Int`: an invariant may not construct |
| `yearendadjustment` | The year-end reconciliation of withheld tax: four tables and a subtraction, stated against **three measures that each are the one before it less a deduction read from a table**. A salary, a total income and a taxable income are three types, so a rule written against one cannot be handed another. The roundings are law rather than presentation — taxable income floored to the thousand, the reconstruction surtax multiplying in between, the year's tax floored to the hundred — and the order is the provision, since flooring first would lose up to 99 yen of surtax base. Nine bands of basic deduction and seven of tax rate are arms, so a reform is a compile error. The special spouse deduction is a two-axis grid republished with every reform, so it is injected: what is domain is that the deduction *has* two axes, not what the cells say this year |
| `filing` | The notification workflow, and where the other seven modules end. **Six spread layers and a state per stage**, so a transition takes the exact state it advances from: a receipt number exists only on a filing that has one, and correcting a returned filing gives back a draft that has none rather than one holding a stale one. Deadlines are computed from the event rather than tabulated — five days on, the tenth of the following month, the tenth of July — and the occasional revision, which is filed "promptly", answers with a case rather than a date a report would treat as real. It is also **where the separation reason reaches its leaf**: the benefit rules never had to tell a dismissal from a closure, and the separation certificate codes them 1A and 1B, which is what makes `EmployerCause` worth being a level of its own |

Modules that are `.sou`-only with no hand-written Java (email/crm/hr/expense/cart/businesstrip)
carry a single minimal `package-info.java` to trigger the processor (javac does not run annotation
processing unless there is at least one source). One is enough however many Souther modules a project
holds: `hr` has eight, generating into eight packages, and still carries exactly one `package-info.java`,
because the processor reads the source directory it is handed and not the list of packages. The smoke tests call the generated
`decoder()`/`encoder()` in a typed way (`decoder()` is `Decoder<…, T>`; `decode(input, Path.ROOT)`
returns `Result<T>`, and `Ok`/`Err` are told apart by pattern match — no wildcard, no cast).

## Dogfooding findings

The `crm` and `hr` examples were written to put the language under a domain that fights back, and what
they produced besides a model is this list. Every entry is a rule a real system enforces, what had to be
written instead, and what would let it be written directly. Each one is also recorded in the `.sou` file
at the declaration that hit it, so a reader meets the finding where the model shows it rather than only
here.

A finding the compiler fixes is removed from here rather than kept as a resolved entry — the model is
rewritten to the form it was asking for, and the commit that does it tells the story; git history is
where that log belongs, not this file.

The seven below are open, and all seven came out of `hr` — the first example large enough for the
scale of a model to be the thing under test.

**F28. A sum every case of which spreads a common data can be read through but not spread from.**
`employee.sou`'s `separate` reads `employee.hiredOn` straight off `ActiveEmployee`, which the language
grants because both cases spread `EmployedCommon`. `Separated { ...employee, … }` is refused — a spread
must be a data value — so the behavior opens the sum with a two-arm `match` whose arms differ only in the
name they bind. The fields a spread would copy are exactly the ones the read already reaches, so letting
the spread follow the read would collapse the two arms into the one line the rule actually is.

**F29. An invariant cannot say that a list is sorted.**
`socialinsurance.sou`'s fifty-row grade table is looked up as "the last row whose lower bound the
remuneration reaches", which is correct only because the rows ascend. `List.length(value) == 50` and
`List.allUniqueBy(.grade, value)` are both statable as invariants; ascendingness is not, because it folds
over neighbouring pairs and an invariant may not fold. The one property the lookup depends on is the one
that cannot be written down. A total `List.isSortedBy` in the standard library would be admissible in an
invariant exactly as `allUniqueBy` already is.

**F30. A table with a hole has no way to say the hole is unreachable.**
`employmentinsurance.sou` writes the benefit-days matrix as a nested match over two banded sums, which is
what makes adding a band a compile error. Two of the twenty-five cells are blank in the printed table —
twenty insured years cannot have accrued before the age of thirty, and an ordinary recipient with under a
year is not entitled at all — and exhaustiveness asks for an arm for each of them with nothing to write
there that says so. Both answer with the neighbouring cell, which is the least wrong number available. An
arm that declares itself unreachable — a surface form for `Never` — is what the table wants.

**F31. A named limit costs its reader a construction authority.**
`weeklyHoursFloor` in `employmentinsurance.sou` is a `let` value, so it is elaborated where it is named,
so `judgeInsuredStatus` has to declare `constructs WeeklyScheduledHours` although it originates no such
value and only compares against this one. A called *behavior*'s construction set stays its own, so the
asymmetry is between a behavior and a value rather than between building and reading. `constructs` is
meant to tell a behavior that creates a value from one that passes an existing one through, and a limit
written down makes every rule that reads it look like the former.

**F32. `List.sum` and `List.product` are declared over `List<Int>` only.**
`attendance.sou` keeps hours as `Decimal`, because half an hour is an hour anybody works, and a list of
them has no sum to take — `souther.list` declares `let sum (xs: List<Int>)`. The fold with a `0.0m` seed
is written out instead. `List.max` and `List.min` are already stated over any ordered element; the same
treatment for the two numeric folds would remove the workaround.

**F33. An output union's member must be a data the module declares.**
`payroll.sou` wanted `computeTaxableAmount : (…) -> Yen | DeductionsExceedGross` and `filing.sou` wanted
`deadlineFor : (…) -> Date | NoStatutoryDeadline`. Both are refused: an imported case may not join a union
declared here (E1606), and neither may a primitive. So each declares a wrapper — `TaxableAmount` and
`FilingDeadline` — for the value alone, and `payroll`'s behavior had to be renamed as well, since a
behavior is capitalised into a class of the same name as its wrapper. Every behavior that answers "a plain
value, or a reason there is none" pays this, and core already has the shape it needs in `Int |
DivisionByZero`.

**F34. Naming an imported value in an `example` row aborts the compiler.**
`payroll.sou`'s rows want `example.attendance`'s published `upliftRates`, which is what the rule is
actually stated against. Writing it that way ends the build with

```text
java.lang.IllegalStateException: the query DerivedDef[named=example.attendance.upliftRates]
depends on itself and says nothing about what that means: [Examples[name=example.payroll, sourceId=4]]
```

— an internal failure rather than a diagnostic, and one that appeared only when the eighth module joined
the build; seven of these files compile with the row written the short way. The four rates are restated
locally as a workaround, which is the one thing in this example a reader should not copy.

## Running

The examples track the compiler's `develop`, so they build against `souther.version` in the root
`pom.xml` — currently a `-SNAPSHOT`, which is not published anywhere. Install it from the compiler
repository first:

```sh
git clone https://github.com/souther-lang/souther.git
mvn -f souther/pom.xml install -DskipTests   # souther-runtime / souther-compiler into ~/.m2
mvn verify                                   # generate → compile → smoke-test every example
```

Two examples are not Maven modules, so `verify` does not reach them — each is built with the tool its
boundary language is actually used with, and each has its own section below:

```sh
cd account       && clojure -X:gen && clojure -X:test   # Clojure
cd issuetracker  && ./gradlew build                     # Kotlin
```

Both still resolve `souther-compiler` from `~/.m2`, so the `mvn install` above is what they need too.

CI runs all three: it checks out `souther-lang/souther` at `develop`, installs it, and then runs
`mvn verify`, the account job, and the issuetracker job. A change in the compiler that breaks an
example turns this build red on the next run.

`ordering` and `issuetracker` actually start Spring Boot, and `issuetracker` also needs the Kotlin
compiler, so **their first build needs network to fetch the starters, and issuetracker's also fetches
the Gradle distribution and the Kotlin plugin**. After that both build offline (`mvn -o` /
`./gradlew --offline`). The other examples run offline from the start.

## Java interop (Spring MVC + jOOQ) — member

The `member` module shows, in a typed way, how the generated types are used from a real app, and it
**actually compiles**. The flow is one-directional.

```text
HTTP → decode (Result<会員ID>) → behavior >-> → match the output cases → encode → HTTP
```

The gist of `member.sou`:

```text
behavior findMember : (id: 会員ID) -> 会員 | 会員なし | 保存データ不正    // no impl → injected from Java
behavior 会員を照会し整形する = findMember >-> 会員を表示用に整形する
// 会員を照会し整形する : 会員ID -> 会員表示 | 会員なし | 保存データ不正
```

Only `findMember`'s success case `会員` flows into the formatting stage; the two failure cases pass
through it and remain in the output (type routing, spec 14.2). The required set `{findMember}` is
inferred by the compiler. The output is **domain outcomes only** — a platform failure such as a DB
outage is not a case: the Java binding throws and Souther passes it through (spec 13.4 / ADR-0029).

The generated `findMember` is an **abstract base class** (it implements `Behavior`) that carries
`protected` factories for the declared unit-data output cases `会員なし` / `保存データ不正`. The
implementation `extends` it and builds the failure cases with the inherited factories (not `new`).

| Java file | Package | Role |
| --- | --- | --- |
| `JooqFindMember.java` | `app.member` | The jOOQ impl that **extends** `findMember`. The success value `会員` is built with the decoder; the failure cases with the inherited `会員なし()` / `保存データ不正()`. DB exceptions are not caught — they are thrown (platform failures pass through) |
| `SoutherBeans.java` | `app.member.web` | Binds the pipeline with `会員を照会し整形する.bind(new JooqFindMember(dsl))` and exposes it as a Bean (spec 19.5) |
| `MemberController.java` | `app.member.web` | `@RestController`. Decodes input with `会員ID.decoder()` (branching on `Result`'s `Ok`/`Err`), folds the domain output cases into an HTTP status (200 / 404 / 500) with a `switch`. A platform-failure exception that passed through is mapped to 503 by an `@ExceptionHandler`. encode returns a plain Map, so Spring/Jackson serialize it to JSON as-is |

The generated-path containment (spec 2.1) holds even across the Java boundary. Because data
constructors are non-public, the controller cannot build data — it only tells the output cases apart
by type and encodes them. Only the effect implementation (`JooqFindMember`) can construct, and only
the cases **the behavior it extends declared**. `new 会員なし()` from another package will not
compile. Reading values out also goes through the encoder (spec 8.5).

> `MemberController`'s `@ExceptionHandler` catches Spring's `org.springframework.dao.DataAccessException`
> and maps it to 503 (the boundary type of ADR-0029). jOOQ's own exceptions are not subclasses of
> that type, so the injected `DSLContext` must have Spring's exception translation enabled (Spring
> Boot's jOOQ auto-config adds it by default; `ordering` verifies a real 503 through this path).

## Spring Boot + H2 + transaction control — ordering

Unlike member, `ordering` does not just **compile** the boundary code — it **actually starts Spring
Boot, connects to H2, and verifies transaction control**. The test brings up embedded Tomcat with
`@SpringBootTest(webEnvironment = RANDOM_PORT)` and sends **real HTTP** (the JDK `HttpClient`) to
`POST /orders` — Tomcat → Jackson → controller → service → transaction → H2 → JSON. Where the other
examples keep external dependencies `provided` (never run), this one resolves the Spring Boot 4
starters at real versions and runs them (DataSource / DSLContext / TransactionManager / schema.sql
execution are all left to autoconfig).

The pipeline joins two injected behaviors with `>->`. **The output is domain outcomes only** — it
has no infra case such as "DB unreachable":

```text
behavior 注文を記録する   : (注文: 注文) -> 注文受付              // INSERT orders (injected)
behavior 在庫を引き当てる : (受付: 注文受付) -> 注文確定 | 在庫不足  // UPDATE stock (injected)
behavior 注文を処理する = 注文を記録する >-> 在庫を引き当てる
// 注文を処理する : 注文 -> 注文確定 | 在庫不足
```

The first stage `注文を記録する`'s success case `注文受付` matches the second stage's input type and
flows in (type routing, spec 14.2). The highlight is that **rollback happens in two ways**.

**A domain failure (out of stock) → rolled back programmatically.** Because Souther represents
failure as a **case rather than an exception**, `在庫不足` arrives as a "returned value", not a
"thrown exception". The controller runs the pipeline inside a `TransactionTemplate`, `switch`es on
the output case, and for `在庫不足` calls **`setRollbackOnly()`** (the same switch also decides the
HTTP status). The order row the first stage INSERTed is rolled back by this.

**A platform failure (DB down, etc.) → auto-rolled-back by exception.** This is not a domain
outcome, so it is not a case. The Java binding (the jOOQ impl) throws, and **Souther passes it
through** (the generated `>->` pipeline does not swallow exceptions). `TransactionTemplate`
auto-rolls-back on the RuntimeException, and the boundary's `@ExceptionHandler` maps it to 503. "The
language has no exceptions, but the boundary Java throws; the distinction is domain outcome vs
platform failure" — that is the policy of spec §13.4 / ADR-0029, and this example demonstrates it.

| Java file | Package | Role |
| --- | --- | --- |
| `JooqRecordOrder.java` | `app.ordering` | The jOOQ impl that **extends** `注文を記録する`. INSERTs into orders and builds the assigned `注文受付` with the decoder. DB exceptions are not caught — they are thrown (platform failures pass through) |
| `JooqAllocateStock.java` | `app.ordering` | Extends `在庫を引き当てる`. Reserves stock with a conditional UPDATE; if zero rows change, the inherited `在庫不足()`. On confirmation the remaining stock is read as a jOOQ `Record` and built with **`注文確定.recordDecoder()`** (raoh-jooq's Record-source decoder, spec 10.6). DB exceptions are thrown |
| `OrderingConfig.java` | `app.ordering.web` | Adds only the generated-side beans: the injected impls, `注文を処理する.bind(...)`, `TransactionTemplate`, and a `Settings` that turns off jOOQ identifier quoting (unquoted names are upper-cased by H2, so they match the lower-case table names in code). DataSource / DSLContext / TransactionManager come from autoconfig. The autoconfig DSLContext goes through a `TransactionAwareDataSourceProxy`, so the first stage's INSERT and the second stage's UPDATE join one transaction (the premise for rollback) |
| `OrderController.java` | `app.ordering.web` | `@RestController` + transaction control. Decodes the body with `注文.decoder()` (destructuring `Ok` with a record pattern, `Err` is 400) and runs the pipeline inside `TransactionTemplate.execute`. One `switch` folds the output cases into an HTTP status (confirmed 201 / out of stock 409) and also calls `setRollbackOnly()` for `在庫不足`. A platform-failure exception that passed through is mapped to 503 by an `@ExceptionHandler` |

The test `OrderingTransactionTest` verifies both rollbacks against a real DB — the 409 for out of
stock, and a **503 for a platform failure** triggered by dropping the stock table — and that in both
cases **no order row remains in the DB** (the first stage's INSERT was rolled back). That is the
evidence of transaction control. As with member, the generated-path containment (spec 2.1) holds
across the Java boundary, and reading values out goes through the encoder (spec 8.5).

> This example and `issuetracker` fetch the Spring Boot starters on the first build, so **they need
> network** (the others run offline). Once they are cached, `mvn -o` — and, for issuetracker,
> `./gradlew --offline` — works after that. The DB
> connection info is in `src/main/resources/application.properties` (in-memory H2), and the schema and
> stock seed are in `schema.sql` / `data.sql`, both loaded at startup by Boot's autoconfig.

## Kotlin + Spring Boot interop — issuetracker

`issuetracker` is the same arrangement as ordering with the boundary language changed: `issues.sou` is
the domain, and everything outside it — the REST routes and the H2 connection — is Kotlin. It starts
Boot and drives every route over real HTTP against H2 in its tests.

The domain has three injected behaviors and three composed ones. The label operations are read →
transform → write, so their sequencing is checked at compile time with the database faked:

```text
behavior findIssue   : (id: IssueId) -> Issue | IssueNotFound   // SELECT (injected)
behavior createIssue : (issue: Issue) -> Issue                  // INSERT (injected)
behavior storeLabels : (issue: Issue) -> Issue                  // rewrite the label rows (injected)

behavior openIssue   : (draft: NewIssue) -> Issue | NoLabels        depends on createIssue
behavior attachLabel : (request: LabelRequest) -> Issue | IssueNotFound  depends on findIssue, storeLabels
behavior detachLabel : (request: LabelRequest) -> Issue | IssueNotFound  depends on findIssue, storeLabels
```

`attachLabel` reads the issue, inserts into its label `Set` and writes it back; an unknown id passes
`IssueNotFound` through without writing. The remaining behaviors (`assigneeOf`, `sharedLabels`,
`countByLabel`, `topLabels`, `busyLabels`) are pure, so they need no injection, and each one has a route.

### Making a javac annotation processor work in a Kotlin Gradle build

Souther generates through a javac annotation processor, and kotlinc is not javac — so the build needs
an order: javac (with `SoutherProcessor`, over the one `package-info.java`) emits the generated
classes, and only then does kotlinc run, with those classes on its compile classpath. Nothing on the
Java side depends on Kotlin here, and everything on the Kotlin side depends on generated bytecode.

That is the reverse of what Gradle's Kotlin plugin does. Within a source set it compiles Kotlin
first and Java second, with the Kotlin output on javac's classpath — so a processor running in
`compileJava` would produce its classes after kotlinc had already needed them.

The build does not fight that ordering; it steps out of it. The processor gets a source set of its
own, `souther`, holding the single `package-info.java`:

```kotlin
val souther by sourceSets.creating {
    java.setSrcDirs(listOf("src/main/java"))
    resources.setSrcDirs(emptyList<String>())
}

tasks.named<JavaCompile>(souther.compileJavaTaskName) {
    options.compilerArgs.add("-Asouther.source=${southerSource.asFile.absolutePath}")
    inputs.dir(southerSource)
        .withPropertyName("southerSource")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(souther.output)
    "southerAnnotationProcessor"("org.souther-lang:souther-compiler:$southerVersion")
    "southerAnnotationProcessor"("org.souther-lang:souther-runtime:$southerVersion")
}
```

`implementation(souther.output)` is what does the sequencing: a source set's output carries the task
that builds it, so declaring it as a dependency puts `compileSoutherJava` before `compileKotlin`
without either task naming the other. It reaches the test source set too, since `testImplementation`
extends `implementation`.

The `inputs.dir` line is not optional. A `.sou` is not a javac source, so nothing else tells Gradle
this compilation reads it — without it, editing `issues.sou` leaves the task `UP-TO-DATE` and the
previously generated classes in place. The jar has to be told as well (`tasks.jar { from(souther.output) }`),
because the generated classes live in another source set's output.

Three smaller things the build pins down: `jvmTarget` is `21`, because kotlinc still defaults to 1.8
and 21 is Souther's runtime floor, so the boundary asks no more of a JVM than the generated code it
drives; `bootJar` is disabled so the artifact stays a plain jar, as the Maven examples' are; and
`settings.gradle.kts` lists `mavenLocal()` first, since `souther.version` is a `-SNAPSHOT` published
nowhere but `~/.m2`.

### What Kotlin brings to the boundary

An output union is generated as a Java `sealed` interface, so `when` over it is exhaustive and the
compiler names the missing case. That is Souther's `match` totality carried across the boundary as a
language feature — the thing account's `case-of` macro had to hand-build for Clojure, and that Java
gets from a `switch` expression.

Souther's `Option` is a sealed interface too, so it maps onto Kotlin's own nullability in one
extension (`orNull()`), and the rest of the boundary uses `?.` and `?:`.

A generated data is a Java record, and that is what Kotlin needs to read its fields as properties:
`issue.id.value` rather than `issue.id().value()`. Kotlin stops resolving a name to the field — and
reporting that the field is not accessible — only when the class file says the name is a record
component. The types are non-null too: `issue.title` is `String` and `issue.labels` is `Set<Label>`,
not the platform types a `@NullMarked` class would hand over through an ordinary accessor. Java and
Kotlin written the old way still compile, since `issue.id()` is what a record component's accessor is
called.

A request body arrives as a plain `Map` and is handed to the derived decoder — there is no Kotlin
data class mirroring the request shape. The shape is already declared in `issues.sou`, and the decoder
is what checks the invariants and reports failures as Raoh issues with their JSON paths. A data class
would duplicate the domain shape and would reject a malformed body in Jackson, before the decoder that
holds the actual rules ever ran. So the module has no `jackson-module-kotlin` dependency: no request
or response shape is a Kotlin type.

The whole Kotlin-side glue is one file, `souther/Souther.kt`: an exception type, `decodeOrFail`,
`orNull`, and an `operator invoke` so a bound behavior is called as `attachLabel(request)` rather than
`attachLabel.apply(request)`. It names no domain type and is written to be lifted out unchanged, the
same way `souther-clj` was.

| Kotlin file | Role |
| --- | --- |
| `build.gradle.kts` | The build. The `souther` source set that runs the annotation processor, and `implementation(souther.output)` ordering it before kotlinc (above) |
| `souther/Souther.kt` | The boundary glue, naming no domain type: `DecodeFailed`, `decodeOrFail` (decode or fail the request), `Option.orNull()`, and `operator invoke` for `Behavior` |
| `IssueRows.kt` | The one place that knows the issue tables. An issue spans `issues` and its `issue_labels` rows, so reading one produces the Map `Issue.decoder()` takes (labels as a list → a `Set` on decode; an absent assignee left out of the Map → `None`). Reading values out of a domain value is plain property access (`issue.id.value`), since a generated data is a record — construction is the guarded direction, not reading |
| `JooqIssueStore.kt` | The three injected implementations, each **extending** the generated abstract base. A Kotlin subclass reaches the base's `protected` factories, so the unit case is built with the inherited `IssueNotFound()`; values read out of storage go through the public `decoder()`, which re-checks their invariants. SQL exceptions are not caught |
| `web/IssueTrackerConfig.kt` | The generated-side beans: the injected implementations, `AttachLabel.bind(...)` and friends, the pure behaviors' `of()`, and a jOOQ `Settings` that turns identifier quoting off. DataSource / DSLContext / TransactionManager come from autoconfig |
| `web/IssueController.kt` | `@RestController`. Every route is decode → one behavior → fold the output union into a status and a body. `@Transactional` on the read-modify-write routes, so a concurrent call cannot drop a label by writing back a set it read too early |
| `web/BoardQuery.kt` | The read side. `countByLabel` / `topLabels` / `busyLabels` are pure behaviors over a whole `Board`, and a summary makes no decision the domain needs to be in on, so this is not an injected behavior: the boundary reads the rows and builds the `Board` through the derived decoder |
| `web/BoundaryErrors.kt` | The two failures that are not domain outcomes: a rejected input is 400 with Raoh's issues, and a `DataAccessException` that passed through Souther is 503 |

| Route | Behavior | Outcomes |
| --- | --- | --- |
| `POST /issues` | `openIssue` | 201 with the stored issue / 400 `no_labels` when the raw label text leaves nothing |
| `GET /issues/{id}` | `findIssue` | 200 / 404 |
| `GET /issues/{id}/assignee` | `assigneeOf` | 200 with the name / 204 when unassigned |
| `POST /issues/{id}/labels` | `attachLabel` | 200 with the issue / 404 |
| `DELETE /issues/{id}/labels/{label}` | `detachLabel` | 200 with the issue / 404 |
| `GET /issues/{a}/shared-labels/{b}` | `sharedLabels` | 200 with the intersection / 404 |
| `GET /labels/counts` | `countByLabel` | 200 with a JSON object of label → count |
| `GET /labels/top?n=` | `topLabels` | 200 with the ranking |
| `GET /labels/busy?atLeast=` | `busyLabels` | 200 with the labels that many issues carry, counts and all |

`IssueTrackerApiTest` boots Tomcat on a random port and drives all of these over real HTTP with
`RestTestClient`, checking the three failure kinds apart from each other: `NoLabels` is a domain
outcome and arrives as a returned case (400 `no_labels`), an empty `label` is an invariant violation
the decoder rejects before any behavior runs (400, with `/label` as the issue's path), and a dropped
table is no case at all — it passes through Souther as an exception and becomes 503.

## Running

Both from the `issuetracker` directory — it is its own Gradle build, not a Maven module.

```sh
./gradlew build      # generate → kotlinc → boot → real HTTP over H2
./gradlew bootRun    # starts on localhost:8080
```

```sh
curl localhost:8080/issues/i-1
# {"id":"i-1","title":"crash on save","labels":["bug","ui"],"assignee":"kawasima"}
curl -X POST localhost:8080/issues/i-2/labels \
     -H 'Content-Type: application/json' -d '{"label":"ui"}'      # 200, i-2 now carries bug and ui
curl -X POST localhost:8080/issues \
     -H 'Content-Type: application/json' \
     -d '{"id":"i-3","title":"flaky test","labels":"Bug, bug , UI"}'  # 201, labels ["bug","ui"]
curl localhost:8080/labels/top?n=1                                    # {"labels":["bug"]}
curl localhost:8080/labels/busy?atLeast=2                              # {"counts":{"bug":2}}
```

## Clojure + Pedestal interop — account

`account` shows that the boundary using the generated types can be **Clojure rather than Java** and
nothing else changes. The domain is the same "read → check → write" as ordering, with two injected
behaviors. The output is **domain outcomes only**.

```text
behavior currentBalance : (account: AccountNo) -> Balance | NoAccount             // SELECT (injected)
behavior updateBalance  : (account: AccountNo, newBalance: Balance) -> Withdrawn   // UPDATE (injected)
behavior withdraw : (request: WithdrawRequest) -> Withdrawn | InsufficientFunds | NoAccount
    depends on currentBalance, updateBalance
```

`withdraw`'s body reads the current balance, passes through if there is no account, otherwise checks
it with `guard current.value >= request.amount.value`, and writes the new balance if funds are
enough. The non-negativity of the new balance `Balance(current - amount)` is discharged at compile
time by the guard just above it. If funds are short it returns `InsufficientFunds` without writing.

The `.sou`-side compile-time check (`fake` + `example` confirm the three cases with no DB) runs
whenever `SoutherProcessor` generates the classes — here that is `clojure -X:gen` (account is a
Clojure/`deps.edn` project, not a Maven reactor module). The account module has no hand-written Java,
so it carries a single minimal `package-info.java` to trigger the processor. **The Clojure app puts
that generated output (`target/classes`) straight on its classpath** (`target/classes` is in
`:paths` in `deps.edn`).

The `:gen` alias is the one place that must not see it, which is what its `:replace-paths` says. A
module the compiler is compiling may not also be on its path — one name cannot mean two modules — and
`target/classes` holds `example.account` from the run before, so generating a second time without
this fails on the module the first run wrote. The alias keeps `souther-clj/src`, where
`souther.build` lives, and nothing else.

### Implementing an injected behavior from Clojure — `proxy` + `decoder()`

The generated injected behaviors are **abstract base classes** (`CurrentBalance` implements
`Behavior<AccountNo, CurrentBalanceResult>`; `UpdateBalance` has `apply(AccountNo, Balance)`).
Clojure implements them with `proxy`. But a `proxy` cannot reach the base's `protected` factories,
so it builds the returned domain values (`Balance` / `Withdrawn` / `NoAccount`) through the
**public generated `decoder()`** — the sanctioned boundary path for turning outside values into
domain data, with data constructors staying non-public (spec 8.5 / 2.1). No gen-class, no AOT.

These interop patterns are packaged as a small reusable library under `souther-clj/` (see its
README), written to be lifted out into its own repo unchanged — its source refers to no domain
type and works by reflection over whatever generated classes the caller passes in:

- `souther.decode` — `decode` runs a `decoder()` over Clojure data (keyword keys accepted) and
  returns `[:ok value]` / `[:err issues]` with issues as plain maps; `construct` builds a case value
  through its `decoder()`.
- `souther.encode` — the inverse: `encode` runs a value's `encoder()` and returns Clojure data,
  unwrapping newtypes (a newtype → its bare value, a record → a keyword map with nested newtypes
  already unwrapped); `unwrap` is that narrowed to a single wrapper. No chains of `.value`.
- `souther.behavior` — `defbehavior`, the `proxy` sugar for an injected behavior; `as-fn`, which
  turns a bound behavior into a plain Clojure fn (called `(f input)`, not `(.apply b input)`).
- `souther.match` — `case-of`, which folds a sealed output union and checks **at macro-expansion**
  that the handlers cover exactly the union's permitted subclasses — carrying Souther's `match`
  totality across the boundary (drop a case and it is a compile error, not a silent fall-through).

| Clojure file | Role |
| --- | --- |
| `account/db.clj` | The H2 DataSource, schema, and seed. The dynamic var `*conn*` is the seam that binds "read → check → write" into one transaction: the boundary rebinds it to the transaction's connection, and both behaviors query through `current`, so `currentBalance`'s SELECT and `updateBalance`'s UPDATE join the same connection |
| `account/behaviors.clj` | Implements `currentBalance` / `updateBalance` with `souther.behavior/defbehavior`, building the return values with `souther.decode/construct` and reading newtype arguments with `souther.encode/unwrap` (no `.value`). Exposes `withdraw-fn` / `current-balance-fn` — the bound behaviors as plain Clojure fns via `souther.behavior/as-fn`. SQL exceptions are not caught — they are thrown (platform failures pass through) |
| `account/service.clj` | The Pedestal boundary. The whole request is the JSON body `{"account": …, "amount": …}`, handed straight to `WithdrawRequest/decoder` via `souther.decode/decode` (the `Amount` invariant `value >= 0` is rejected here → 400, and the Raoh issues are returned in the body). Then it calls `withdraw` (a fn) inside `with-transaction`, folds the output with `souther.match/case-of` (miss a case and it will not compile), and `souther.encode/encode`s the result value to the JSON body — Withdrawn 200 `{account, newBalance}` / InsufficientFunds 409 / NoAccount 404 |
| `account/server.clj` | The `-main` that creates and seeds H2 and starts Jetty |

`InsufficientFunds` / `NoAccount` arrive as **returned values**, and no write happened on those
branches, so there is nothing to roll back. Wrapping read → check → write in one transaction is for
atomicity — so a concurrent withdrawal cannot interleave and double-spend. A platform failure (a SQL
exception) is not a case: it passes through `withdraw` untouched, `with-transaction` auto-rolls-back,
and it propagates to the framework. The full platform-failure → 503 + rollback treatment against a
real DB is shown by `ordering`, so account does not repeat it.

## Running

Generate the types first, then run Clojure (Clojure lives outside the Maven reactor, in its own
`deps.edn`). Generation itself needs no Maven — the `:gen` alias runs `SoutherProcessor` through the
JDK compiler API (`souther.build/generate!`), with `souther-compiler` on the alias classpath only:

```sh
cd account
clojure -X:gen                                   # .sou → target/classes (the .sou examples are checked here too)
clojure -X:test                                  # the souther-clj library, behavior+DB, and Pedestal boundary tests (20 of them)
clojure -M:run                                   # starts on localhost:8890
```

`clojure -X:gen` is the only generation path for account: unlike the other examples it is not a
Maven reactor module, so `mvn … verify` does not build it — its `.sou` is checked by the `:gen` run
above.

```sh
curl localhost:8890/accounts/acc-1                                            # {"account":"acc-1","balance":1000}
curl -X POST localhost:8890/withdrawals \
     -H 'Content-Type: application/json' -d '{"account":"acc-1","amount":300}'  # {"account":"acc-1","newBalance":700}
curl -X POST localhost:8890/withdrawals \
     -H 'Content-Type: application/json' -d '{"account":"acc-1","amount":5000}' # 409 {"error":"insufficient_funds","shortfall":...}
```

> Clojure / Pedestal / next.jdbc are fetched from clojars / Central on the first run, so **it needs
> network** (once they are in `~/.m2` and gitlibs, no more). `mvn -o verify` does
> not include this Clojure app (a separate toolchain). The account module itself is generated and
> checked offline, like the others.
