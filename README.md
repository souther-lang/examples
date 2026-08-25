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

The [tutorial](https://souther-lang.org/tutorial/) builds `businesstrip`'s model from scratch,
starting from the fields the business trip requests are recorded in today and refining it one
`souther examples` report at a time. It needs nothing but the `souther` executable — no Maven
module, no generated code. It names its types in English; the model in this directory names them in
Japanese, and is the fuller one the tutorial ends by pointing at.

## How generation works: a build-tool plugin

`.sou → .class` is done by a plugin the build declares: `souther-maven-plugin` here,
`souther-gradle-plugin` in `issuetracker`. It compiles the `.sou` files in `src/main/souther` into
`target/classes` before javac runs, so the hand-written code (and the smoke tests) **compile
directly against those generated types**. No exec step and no separate module.

The whole Maven wiring is this, set once for all modules in the root `pom.xml`:

```xml
<plugin>
  <groupId>org.souther-lang</groupId>
  <artifactId>souther-maven-plugin</artifactId>
  <version>0.1.0</version>
  <configuration>
    <southerVersion>0.1.0-SNAPSHOT</southerVersion>
  </configuration>
  <executions>
    <execution><goals><goal>compile</goal></goals></execution>
  </executions>
</plugin>
```

The compiler is not on the plugin's class path. `<southerVersion>0.1.0-SNAPSHOTalready uses and runs behind `souther-build-api` in a class loader of its own — so the plugin and the
Souther it runs are released on their own terms. A project naming no version gets the Souther the
plugin release was verified against. The plugin also checks that the pom declares `souther-runtime`
at the same version rather than adding it, because what a plugin adds is not in the pom you publish.

Gradle is `id("org.souther-lang.souther")`, and there the runtime is added rather than checked;
`issuetracker/build.gradle.kts` is the whole of it.

The plugin is what a project written only in Souther needed. Before it, generation ran as a javac
annotation processor, and javac does nothing at all when a compilation has no Java source: every
module with no Java of its own carried one `package-info.java`, written in a language the project had
chosen not to use, so that the processor would run. Those files are gone. `account` still has one —
it drives generation from Clojure through `souther-clj`, which runs the processor over a trigger
directory the same way.

A `.sou` edited without `clean` is compiled. Under the annotation processor it was not:
maven-compiler-plugin's incremental check reads Java sources only, so a `mvn verify` after a `.sou`
edit recompiled nothing and reported success over the previous run's classes.

Importing a module another project compiled needs nothing beyond the dependency itself. `sharedmoney`
publishes `shared.money`; `invoicing` depends on that jar, has only its own `.sou` under
`src/main/souther`, and writes `import shared.money ( Amount )`. What the module declared is read off
the classes on the compile classpath, which is what depending on a jar already puts there, and
`invoicing`'s output holds no `shared/money` class — the dependency's classes are its own build's.

A compile error is reported the way the CLI reports it — the title, the position, the offending line
with a caret, and the hint. `<languageTag>` (or `-Dsouther.lang=ja`) picks the language of the
message; without it the compile follows `SOUTHER_LANG`, and English when that is unset too. The
machine's own locale is never read, which is what `souther --lang` does as well.

## Modules

| Module | What it shows |
| --- | --- |
| `cart` | List combinators `map`/`filter`/`all`/`any` (`souther.list` derives them from `fold`) + the empty list `[]`. Actually runs the behavior `quote` and checks its result cases |
| `catalog` | The product structure, and **the first self-referential `data` here**: a `Component` holds a `List<Component>`, which is the shape ADR-0038 was written for and which nothing in this repository had used. What the recursion costs is a declared return type on every helper that recurses; what it buys is `explode`, which walks the whole structure with the multiplier carrying down, and `bomDepth`, which answers a question a flat parts list cannot. Every call descends into `node.parts` — a sub-term of an argument, so the walk is structural and total by default, and nothing here needs `partial`. `CatalogTest` sends a structure three levels deep through the derived codec and back, which is the claim ADR-0038 makes about self-referential data and the one an `example` cannot check. A cycle is not representable at all, because the parts are nested rather than named by a key — so the check belongs with whoever turns parent/child rows into this tree, not with a behavior here, and an invariant could not have expressed it anyway (an invariant may not recurse, the same reason `inventory`'s EAN-13 check digit is a behavior) |
| `businesstrip` | A business trip application, written 1:1 from a specification DSL. **The states of the application are the sum**: the nine cases of `出張申請` each carry only what that state has (`提出済み` has a submission time, `承認完了` has a settlement amount), and a behavior's input is a state type — so approving an application nobody submitted is not a call you can write. Field composition is the `...出張申請共通項目` spread, layered twice (`出張完了共通項目` spreads it again and the completed states spread that). Sums nest — `費用負担区分`'s case `自社負担` is itself the sum `立替 \| 仮払い \| 会社カード` — and the rule "only an out-of-pocket 立替 line is reimbursed" is exactly the two-stage `match` that opens one level at a time. The derived codec folds the nesting the other way, dispatching over the leaves, so the JSON stays one flat `{"type": "立替"}`. The expense categories hold different fields per case, so a total pulls each amount out through a `match` before `List.sum`. Preconditions are outcomes rather than aborts: an empty rejection reason returns the `却下理由なし` case, and the newtype `却下理由` is built after the `guard` that discharges its invariant. Where the DSL writes `事前承認日時? AND 事前承認者ID?`, the model uses a sum (`事前承認なし \| 事前承認あり`) so that a timestamp with no approver cannot be represented. The smoke test drives the whole run — decode a draft, submit, pre-approve, complete, request and grant final approval — and checks the settlement is the out-of-pocket lines only |
| `joboffer` | A crowdsourcing job offer: **a sum of sums of sums** (依頼 → プロジェクト依頼 → 精算方式 → 固定精算 → 予算 → 範囲予算), with the value-less cases of an enum as unit data, declared by the sums that name them and nowhere else. Ported from [kawasima/validation-modeling](https://github.com/kawasima/validation-modeling)'s `raoh` version, where the same model is a hand-built decoder carrying the constraints; here the constraints are the newtypes' invariants and the decoder is derived from them. It reads a Jackson `JsonNode` through the generated `jsonDecoder()`, which is also how a date arrives as JSON text — `realworld` reads its request bodies the same way — and it runs Spring Boot for real, with no database, since both behaviors are pure |
| `issuetracker` | A small issue tracker, and the **Kotlin** case: the boundary around the domain — REST and the H2 connection — is Spring Boot + Kotlin (below). Showcases the `Set` module (an issue's `labels` are a `Set<Label>` — the derived codec dedups a JSON array — and `openIssue` cleans the raw label text with `Set.map` + `Set.filter` without leaving the set for a list first), the `Map` module (`countByLabel` builds a `Map<String, Int>` with `Map.updateOrInsert`; `topLabels` ranks those counts and splits the ranked pairs with `List.unzip`; `busyLabels` keeps the entries a threshold holds for with `Map.filterEntries`; `groupByAssignee` buckets the issues themselves into a `Map<String, List<IssueId>>` with `List.filterMap` + `List.groupBy`, the optional assignee dropping out without a stand-in value), `List.flatMap` to gather every label occurrence across the board, `Some(Assignee(name))` destructuring of an optional assignee, and three injected database behaviors whose read → transform → write sequencing is checked with `fake` + `example`. Like ordering it actually starts Boot and connects to H2, and — being the Kotlin case — it is built with Gradle rather than Maven |
| `member` | Member lookup. A `required behavior findMember` (outside-world dependency) + type routing `>->`. Actually compiles the Spring MVC + jOOQ boundary code (below) |
| `account` | Account withdrawal, "read → check → write". Binds `withdraw` (which has two injected behaviors) from **Clojure + Pedestal rather than Java**, connected to H2 inside a transaction (below). It shows that the generated types are used the same way even when the boundary language changes |
| `ordering` | Ordering + stock reservation. Two injected behaviors joined with `>->`, and it **actually starts Spring Boot, connects to H2, and shows transaction control**: if the second stage returns the `OutOfStock` case, the first stage's INSERT is rolled back too (below). Also a pure `report` over a recorded order — a sales summary showcasing `distinct` (the old standalone `sales` example, folded in here) |
| `tax` | Consumption tax, and the module where **a rate is a fact with a date on it**. It has been three per cent, five, eight and ten, and which applies is decided by the day of the transaction, so what is injected is not a rate but a `RateSchedule` — every revision since 1989 and the day each took effect, ascending, which is the one property the lookup depends on and therefore the one the type states. A category whose first change is later than the day asked about answers `NoRateYet`: the reduced rate did not exist before October 2019, and a line dated earlier has no reduced rate rather than an old one. The other half is **the total rounded once and then shared out**: `allocateTax` floors each line and puts what the flooring lost onto the last line, and `TaxAllocation`'s invariant is that the lines sum to the stated total — so an allocation that does not add up is not a value anybody can hold. `taxRoundedPerLine` sits beside it and is a yen short, which is the difference the invariant exists to keep. Amounts stay `Int` (yen has no fraction) and only the rate is a `Decimal`, so the conversion is visible exactly where a value stops being yen — `Decimal.fromInt(net) * rate.value` — and comes back with the rounding named: `Decimal.toInt(…, FLOOR)`. The rate is also *written down* in the domain: `String.fromDecimal(Decimal.round(…))` turns it into the `"10%"` a qualified invoice states. jOOQ reads the whole history out of the schema's one `NUMERIC` column and the `DATE` beside it; which row applies is not the boundary's decision |
| `inventory` | The warehouse side, and the module where **a quantity carries its unit**. `Eaches` and `Cases` both wrap `Int` and neither compares with the other — `e >= c` is a type error rather than a bug found in production — so the only way across is `toEaches` / `toCases`, each naming the pack size that makes the conversion true, and the remainder a partial case leaves is `Eaches` and never `Cases`. cart counts units without naming one, which is where the `.value` shows up and is the honest place for it. It is also where **the stock on hand is derived rather than held**: `stockFrom` folds receipts, issues and stocktakes into the snapshot — a stocktake replaces the running figure rather than adjusting it, because a count is somebody at the shelf — and a figure that would go negative is reported instead of aborting. The injected `readStock` sits beside it, so the two ways a system can answer "how many" are written down together rather than argued about: `account` keeps a balance and writes to it, this reads one off the movements. The rest is `allocate` (read → index → aggregate check → write), EAN-13 `inspectBarcode` (a check-digit fold with `List.mapIndexed` / `List.sum`), `putAway`, and `baySlots` — which *builds* shelf codes rather than only checking ones that arrive, with `List.rangeInclusive` over the levels of a bay and `String.padLeft` widening each number to the two digits `Location`'s invariant demands |
| `shipping` | Dispatch, and the first module two import hops from the shared vocabulary (shipping → inventory → cart). `exposing` lists a module's own definitions and nothing it imported, so there is no re-export and a shared vocabulary is imported by everyone that speaks it. `List.sortBy` over a `Location` key walks a pick list in shelf order without projecting to `.value`; `List.partition` splits it into what ships now and what waits, read back with `let (ready, waiting) = …`; `Map<Date, Int>` is a behavior output, so the shipment calendar crosses the boundary as `{"2026-07-25": 2}`. `Date` and `DateTime` both offer `addDays`, so every temporal call stays qualified |
| `billing` | The money side, and the first module under a diamond — it imports `ordering` and `tax`, both of which import `cart`, and all three agree on cart's vocabulary because a module is resolved once per compile. It is where `Amount` is owned: `returns` imports it rather than declaring one, since two modules exposing the same name cannot both be imported by a third. **A due date lands on a day somebody works**: terms give a nominal date and the calendar walks it to a business day in the direction the trading agreement names, as `List.rangeInclusive` + `List.find` over ten candidates rather than a recursive step that would not be structural. The weekday is counted from a Sunday the calendar carries, because the standard library reads a date's year, month and day and not its weekday. **A correction is a new document**: an invoice states an amount on a day and that stays stated, so a price error is a `CreditNote` against it and what is owed now is a fold over both — there is no `cancelled` flag and no field a correction reaches. Crediting more than was invoiced is refused, because that is a payment out and a different authorisation |
| `returns` | The last leg, and the deepest module here: it imports `billing` and `shipping`, each already a hop or two above cart, so three levels of import and a second diamond. `constructs` may name a type another module exposes, which is why what a context originates is the model's decision rather than the compiler's — this reads billing's `Amount` and produces a `Refund` of its own, because a refund is an event this context records. `Set` is the working collection (what came back, what was expected, what is outstanding, related by `difference` / `intersection` / `union` / `contains`), `Map.intersection` and `Map.difference` reconcile two keyed readings of one shipment, and a refund clamped at zero says an over-refund would be a different event rather than a sign |
| `ledger` | Where order-to-cash lands in the books, and where **double entry is a condition on construction**. A `JournalEntry` whose debits and credits differ is not a value: the rule is an invariant, so a lopsided entry aborts where it was built rather than being posted and argued about at month end. It is expressible because an invariant may name a total helper — `sideTotal` is a `List.fold` — and it may not construct, which is why the two sides are compared as bare `Decimal`s and never as `Amount`s. `postCreditNote` is what billing's refusal to rewrite an invoice looks like once it reaches the books: the same accounts, the sides swapped, its own row, its own date. `balanceOff` states the same law one level up, and `LedgerTest` watches the decoder refuse an entry that arrives a yen out |
| `crm` | The CRM core, and the first of the six Souther modules in the CRM and sales-force-automation example. It carries forward what the old `contact` module was the only place to show — a sum decoded and encoded through its `"type"` discriminator, an unknown tag as a decode failure, and a newtype's format invariant enforced when the value is reached *through* the discriminator — now on `ContactPoint`, whose three cases carry payload. **A pair of optionals becomes a sum**: Salesforce's nullable Email plus nullable Phone spells four states and the one that matters, reachable by nothing, is representable; three cases remove it, and `title: JobTitle?` sits three lines below as the optional that is right because no rule depends on it. **The lead's states are the sum**, with the touch record spread onto the common items so only the states past New carry it, and a converted lead cannot be converted again because `convertLead` takes a `QualifiedLead` and produces a `ConvertedLead` — there is no guard to forget. Conversion reports **every** blocking reason rather than the first, which is what building the reasons as a value rather than as a `guard`'s departure buys. Duplicate screening keeps Salesforce's two standard rules with their two different match keys: accounts on the email domain, contacts on the exact address, both checked against books passed in as data. It is also where **money is an amount and the currency it is in**: an `Amount` on its own is a number, and six hundred of something cannot be added to nine hundred of something else, so `Money` pairs the two and `convert` is the only way to a figure that can be summed. `RateTable` holds its own base at one, which is the invariant, so a figure already in the reporting currency takes the same path as any other rather than an `if` somebody forgets. One injected behavior, bound from plain Java in the smoke test with no framework |
| `org` | The role hierarchy, and **the module that shows which way a hierarchy should point**. A `RoleNode` holds the roles reporting to it. Pointing the other way — `manager: UserId?` on each person, which is how nearly every system stores it — is the one shape that cannot be walked here: following an id upwards is a `Map` lookup, and a looked-up value is not a sub-term of anything, so the walk is not structural recursion and every helper would need `partial`. Nested downwards, `chainTo` (the line of command to one person), `teamOf` (everybody at or below a node, as a `Set`, because the question is membership) and `widestSpan` (the largest number of direct reports anywhere, which is a maximum over the whole structure and not of any one node) are all total by construction. The same choice removes the cycle: rows that name a parent can name an ancestor and need a check nobody can write as an invariant, and a tree of nested nodes cannot be written cyclically at all. Two unrelated questions read it — who signs a discount (`quoting`) and whose numbers a manager's forecast is made of (`forecasting`) — so one structure answers both instead of two flattened copies drifting apart |
| `pipeline` | The SFA core: **the ten Salesforce stages, and the stage is the type**. Every transition takes the exact stage it advances from, so there is no stage argument, no stage check, and no way to write a call that sends a proposal to a deal nobody qualified — `PipelineTest` says so by omission, since the illegal transitions are absent because they do not compile. Six spread layers, because a deal accumulates commitments in six steps and each step is what the next stage needs to exist; that is what makes `withdrawProposal` honest, since pulling a quote returns a state that has no quote number rather than one holding a stale value. The probabilities and forecast categories are the real mappings as ten-arm matches, so adding a stage is a compile error in three places rather than a deal that quietly weighs nothing. `LossReason` nests because win/loss review asks whether somebody else won it or nobody bought, and only the displaced kind names a rival — while the codec folds to the leaf, so the JSON stays one flat tag. The seam with `crm` is two calls with a Java line between them: `crm` builds the account and the contact, `pipeline` builds the opportunity and answers with **its own** `NoOpportunityRequested`, because an output union is built from the cases the module declares |
| `activity` | The activity log. Salesforce's polymorphic `WhatId` — one column holding an account, an opportunity or a lead id — is a sum, which is why this module imports both `crm` and `pipeline`. Three kinds of activity with different fields, so a total over them opens each kind before it can read a date (a meeting's is a `DateTime` that comes down to a `Date` first). `Set` where the question is membership: multi-threading a deal is the best-known loss predictor and two meetings with one person are not two people. `Map.updateOrInsert` folding to a **minimum** rather than a sum, because recency is the freshest touch and not the total. `Map<Date, Int>` as an output, keyed by a temporal that crosses as its ISO form. And the next-step playbook — an eight-arm match over the open stages prescribing what that stage's exit criterion needs, which is where the pipeline stops being a report and starts telling somebody what to do on Monday |
| `quoting` | Quote lines and CPQ. **Where `pipeline` makes the states the type, this module makes an attribute the sum**: a quote is the same document before and after approval, so its `ApprovalState` is a four-case sum on a field where Salesforce has a flag and three nullable columns — one of whose cases, approved automatically, has nobody to name. A product invariant across three fields pins `net = quantity × list × (1 − discount)`, and **every ratio rule in the file is written as the multiplication it implies**, because a division inside an invariant aborts on a zero denominator instead of rejecting the value. Both CPQ caps are real: per line, and **blended**, which is the one that holds — a rep who needs thirty per cent off takes it all off one line and every per-line check passes. **Approval is a chain rather than a person**: the discount picks a level of authority and `org`'s hierarchy names the people at it, so the matrix most systems maintain by hand and let go stale is two rules that never need re-entering. `PendingApproval` carries who the quote is waiting on and who comes after, so approving advances it and only the last signature approves; somebody further up cannot sign early, and one rejection ends it without asking the rest of the chain to overrule a no |
| `forecasting` | The weighted forecast, and the module that **builds its own map key**: `FiscalPeriod` is `"FY26-Q3"`, assembled from the close date and the org's fiscal-year start month with `Date.year` / `Date.month` / `Int.floorMod` / `String.padLeft`, then checked by the same regex a key arriving from outside would face. The weighting is the project's one real division, so the scale and the rounding mode are named at the call. It is also where **`Map.union` is deliberately not used**: a manager's forecast is the reps' forecasts summed, and union is left-biased and never merges values, so it would silently drop a rep's number on a shared period — combining two values under one key is a domain decision, and the fold with `updateOrInsert` is where that decision lives. This module used to sum a dollar deal and a yen deal without noticing, which is what carrying an `Amount` and a `CurrencyCode` side by side buys you; now every deal is converted on the way in, a `Forecast` states the currency it is in, and a deal in a currency the rate table does not price stops the forecast and names the currency instead of quietly dropping out of the total. **A manager's team is the subtree**, walked down `org`'s hierarchy rather than handed in as a list, so a rep who moves is counted where they now report and counted once — and two currencies under one manager is a refusal rather than a sum. A two-argument injected `quotaFor`, which is the third generated shape after `pipeline`'s zero-argument one and `crm`'s one-argument one. The only module here with no `exposing` clause, sitting beside `quoting`, which writes one although nothing imports it either |
| `employee` | The vocabulary the other seven HR modules speak, and the first of the eight in this repository's largest project. **The person is four data with four reasons to change** — an identity that is only ever corrected, a name that changes with a notification behind it, terms that change when the contract does, and a remuneration that changes at a pay review — and the employment states are a sum spreading all of them. The remuneration is four fields because **four rules add them up four different ways**: a grade is decided from everything paid in the month, an occasional revision is triggered by the fixed part, the short-time coverage test reads the scheduled wage, and an overtime uplift is computed on a base the statute strips family, housing and commuting allowances out of. `attainedAgeOn` is one line and is what a great many payroll systems get wrong: a person attains an age on the day *before* their birthday, so somebody born on the first of a month starts paying long-term-care premiums a month earlier than their birthday suggests. My Number's check digit is a behavior rather than an invariant, since an invariant may not fold — the same division `inventory` draws for EAN-13 |
| `dependents` | Who counts as a dependant, asked twice of the same person and answered differently. **Two measures of one thing, kept apart by their types**: the social-insurance test reads revenue (everything coming in, taxable or not) and the income-tax test reads income (revenue less the employment-income deduction), both wrap `Int`, and neither compares with the other — so the rule that reads revenue cannot be handed income. A spouse earning 1.2 million yen is a dependant for health insurance and is not one for tax, and that pair of answers is the module. Living apart carries the remittance **on the case**, so the support test cannot be written against a person whose remittance nobody recorded. Both judgments report every failing reason rather than the first, built as a value and attempted against the blocked case's own invariant. Which category a dependant falls in is decided here; what the category is worth is `yearendadjustment`'s, because the categories have stood for decades and the amounts moved in 2025 |
| `socialinsurance` | Health and pension insurance, and the module the domain fights back in. **The fifty-row standard-remuneration table is data and the lookup a fold**, which is what a table that size wants — all three of its rules are on the type, ascendingness included, since `List.fold` is total and an invariant may name a total helper, so the one property the lookup depends on is the one the type states. The thirty-two pension grades are the health grades shifted by three and clamped, which is what the statute says they are rather than a second table to keep in step. Coverage is two tests — the three-quarters rule and a four-condition short-time rule asked only of whoever it turns away — answered by two attempted constructions, and the answer is **which of the two insurances apply**, since pension coverage ends at 70 and health at 75 and there is no window where pension applies and health does not. A three-month window is a product of three fields, because three is an arity and not a size. The employee's half of a premium rounds HALF_DOWN because the statute drops 50 sen, and the employer pays the rest — so the halves of a 10,901-yen premium are 5,450 and 5,451 |
| `employmentinsurance` | Unemployment insurance, and **a five-by-five statutory matrix as a nested match**, so adding a band is a compile error in the five places that have to answer for it. Two of the twenty-five cells are blank in the printed table — twenty insured years cannot have accrued before thirty, and an ordinary recipient with under a year is not entitled at all — and each of those arms answers `unreachable` with the reason, so the blank stays blank instead of printing the neighbouring cell's number. The 31-day employment expectation is **derived rather than asked for**, read off the contract term, so there is no second field for the same fact to be recorded inconsistently in. The separation reason is read at the *outer* level here: every employer cause makes a specified-eligible recipient and none is restricted, while the dismissal that is the worker's own fault sits outside that group in `employee` for exactly this reason |
| `attendance` | Working hours, and the module that produces **hours and multipliers and never money** — the uplift rates are labour law and an hourly rate is pay policy, so multiplying them is `payroll`'s business. Late-night hours are tallied on their own and counted again rather than a fifth bucket being invented for the overlap, so overtime after ten at night is 50 per cent by addition and holiday work after ten at night is 60, with no arm anywhere that says so. Work on the statutory holiday is holiday work and never overtime, which is why it misses the sixty-hour count. The overtime agreement is judged under two regimes with five reasons, and the four figures the special clause is bounded by are not one measure — two count holiday work and two do not. Annual leave reads a five-by-seven accrual table **whose row is chosen by two fields**, because thirty hours over three long days is the full entitlement and not a proportional one |
| `payroll` | Gross to net, and the module the other four feed into. **Two months in one pay slip**: premiums are withheld a month in arrears, so the premium on a July slip is June's, and `PayMonth` and `ContributionMonth` are two types with the statute as the behavior between them. **Two rounding rules a screen apart**: an uplift settles HALF_UP and a premium HALF_DOWN, because one circular carries 50 sen and the other drops it. A deduction is not an amount but an amount with a basis — wages are paid in full and only what a statute or a labour-management agreement allows may come out — so an agreed deduction carries the agreement it rests on and a line with no basis is not representable. The slip's arithmetic is an invariant on the type, which is also why the fold behind it answers a bare `Int`: an invariant may not construct |
| `yearendadjustment` | The year-end reconciliation of withheld tax: four tables and a subtraction, stated against **three measures that each are the one before it less a deduction read from a table**. A salary, a total income and a taxable income are three types, so a rule written against one cannot be handed another. The roundings are law rather than presentation — taxable income floored to the thousand, the reconstruction surtax multiplying in between, the year's tax floored to the hundred — and the order is the provision, since flooring first would lose up to 99 yen of surtax base. Nine bands of basic deduction and seven of tax rate are arms, so a reform is a compile error. The special spouse deduction is a two-axis grid republished with every reform, so it is injected: what is domain is that the deduction *has* two axes, not what the cells say this year |
| `filing` | The notification workflow, and where the other seven modules end. **Six spread layers and a state per stage**, so a transition takes the exact state it advances from: a receipt number exists only on a filing that has one, and correcting a returned filing gives back a draft that has none rather than one holding a stale one. Deadlines are computed from the event rather than tabulated — five days on, the tenth of the following month, the tenth of July — and the occasional revision, which is filed "promptly", answers with a case rather than a date a report would treat as real. It is also **where the separation reason reaches its leaf**: the benefit rules never had to tell a dismissal from a closure, and the separation certificate codes them 1A and 1B, which is what makes `EmployerCause` worth being a level of its own |
| `identity` | Who somebody is, how they prove it, and who they follow — the first of the three modules behind the [RealWorld](https://docs.realworld.show/) API. They are `blog.*` rather than `example.*` because what they model is a blogging platform and the RealWorld API is one way to reach it; naming them for the API would have put the domain under a name it does not know exists, which is the whole shape of the module. It is the one example here **whose JSON shape is somebody else's decision**. Every other module lets the derived encoder's output be the response, because the shape of the data and the shape of the JSON are both its own call; this one answers to a specification and to frontends already written against it, and `ConduitJson` is the single file where the difference is paid. **A `User` does not carry its password hash**: a `User` is what a response is built from and a derived encoder writes every field it is given, so the hash travels beside it and `findLogin` answers a `Credentialed` that never leaves the behavior reading it. A failed login answers `InvalidCredentials` whichever half was wrong, because an answer that told an unknown address apart from a wrong password would tell a stranger which addresses have accounts. `updateUser` looks up only the names that actually moved, so re-saving a profile unchanged costs no query and an address only this user holds is never reported as taken against itself. Following is a composed behavior because it has a rule (not yourself) and unfollowing is an injected write because it has none — which is the shape saying which operation carries a decision |
| `articles` | What an article is, how its slug is made, and who may change it. **An `Article` is an `ArticleSummary` with its body**, and that layering is a fact about the read rather than about the JSON: the listing SQL does not select the body column, the specification's list entries carry no `body`, and a summary is a whole value rather than an article with a field left out. `slugText` answers a `String` and not a `Slug`, because a title of nothing but punctuation has no slug in it and the newtype's invariant would abort — so the emptiness is discharged by a guard and `TitleHasNoSlug` is a case the boundary folds. **Authorization is a returned case**: `updateArticle` and `deleteArticle` answer `NotTheAuthor` and the controller only chooses 403, so no boundary code tests an author. **A search is a value** — `Limit` and `Offset` carry their bounds as invariants, so `?limit=1000` is refused by the decoder and the SQL is never shown it — and the global list and the personal feed are two cases of a sum rather than one query with nullable fields, because a feed has no tag to filter by and a global list has no followees. `FeedQuery` carrying a `Set<Username>` is the first case in this repository to hold a `Set` |
| `comments` | Comments on an article, and the module where **the shape says which of two operations carries a decision**. Writing one asks the domain nothing — anybody logged in may comment — so there is no composed behavior wrapping the write and the boundary calls it directly; deleting one carries exactly one rule, so it goes through `deleteComment` and answers `NotTheAuthor`, the same way articles answer it about editing. It is the third module in the chain: it imports `articles` for the `Slug` a comment hangs on and `identity` for the `Profile` that wrote it, and `articles` imports `identity` too, so the two paths to `Profile` meet and agree |
| `whodunit` | A logic puzzle solved at compile time, and **each `example` row is one puzzle**: the clues are the row's inputs, the verdict is its expected value, and the build passing proves each puzzle has exactly the answer its row states. The candidate worlds are a fixed product written as nested `flatMap` over the same three rooms — nothing recurses, so nothing needs `partial` — and `allDistinctBy` keeps the seatings injective. The verdict is a three-case sum (`Unraveled \| NoConsistentStory \| StillAmbiguous`), so an over- or under-constrained puzzle is an answer rather than an error. The pattern generalises: enumerate a small domain, filter by the rules, and state the survivors in an `example` — the same bounded exhaustive check `billing`'s `unpayableDueDates` runs over a month of due dates |

Modules that are `.sou`-only with no hand-written Java (crm/hr/businesstrip) have no
`src/main/java` at all: the plugin compiles the source directory it is handed, however many Souther
modules that directory holds — `hr` has eight, generating into eight packages. The smoke tests call the generated
`decoder()`/`encoder()` in a typed way (`decoder()` is `Decoder<…, T>`; `decode(input)` returns
`Result<T>`, and `Ok`/`Err` are told apart by pattern match — no wildcard, no cast). A `Path` is
passed only where the decoder is handed one field of a larger input and could not otherwise know
where it came from.

## Dogfooding findings

The examples were written to put the language under domains that fight back, and what they produced
besides a model is this list. Every entry is a rule a real system enforces, what had to be written
instead, and what would let it be written directly. The list lives here and only here: a `.sou` file
says what the domain is and how it is written in Souther, and carries none of this.

A finding the compiler fixes is removed from here rather than kept as a resolved entry — the model is
rewritten to the form it was asking for, and the commit that does it tells the story; git history is
where that log belongs, not this file. One that has been filed against `souther-lang/souther` carries
the issue on its heading, with the repro that was compiled standalone before filing.

**F24 (souther#290) — the same expression is silent written inline and warned about once it is
named.** `Eaches(Int.floorMod(e.value, pack.value))` written inline draws nothing; bound to `leftover`
first and constructed from afterwards it warns that the guards do not establish the invariant. The
silence is not a proof — the inline form stays just as silent against an invariant the expression can
actually violate, because a call is a term the checker cannot express and a non-expressible invariant
says nothing. Naming the call is what makes it expressible, and then it is unproven. A `let` over
ordinary arithmetic does carry what is known about it; a `let` over a stdlib call carries nothing.
`inventory`'s `toCases` and `billing`'s credit-note arithmetic both sit on this, and both keep the
name.

**F27 — silence is not proof.** `Eaches(Map.fold((acc, k, v) -> acc + v, 0, m))` can be negative and
draws no warning, while `Eaches(line.quantity)` — equally unproven, and readable — draws one. The
checker speaks up about what it can see and says nothing about what it cannot, which reads to a
newcomer as the opposite. F24 is the same rule met from the other side: there the two answers land on
the two spellings of one expression.

**F29 — a `Set` crosses the boundary in no particular order.** The derived encoder emits whatever
order the set happens to hold, so a response body is not byte-stable and a test cannot assert on the
array. `CatalogTest` compares membership instead, which is the right assertion here and the wrong one
to be forced into everywhere.

**F30 — an import a fixture needs is reported as unused.** A row's input is decoded, and a sum
written there as a bare case name (`contract = Indefinite`) is read against the sum's own name, so
`ContractTerm` has to stay on the import list. `E1922` does not count that as a use: `socialinsurance`
and `yearendadjustment` are both told the name is never used, and taking it off turns the row into
`E1903 — contract.type: is required`. Two answers about one name, and only one of them compiles.

**F31 (souther#298) — a collection is written one way in a body and another in a fixture.** In a body
`[ … ]` is a `List` and only a `List`, so a `Set` field is filled with `Set.fromList` — `billing`'s
`payerCalendar` does. A fixture is decoded instead, so the same brackets there are whatever the field
holds, which is how the row two screens below writes the same holiday. The asymmetry runs the other
way too, and further: a fixture takes `Map.fromList([ ])` and refuses `Map.empty`, which a body names
freely. Of two spellings of one empty map, the call is allowed in both places and the value in only
one.

## Running

This is the `develop` branch, which tracks the compiler's `develop`: `souther.version` in the root
`pom.xml` is a `-SNAPSHOT`, published nowhere, so the compiler is installed from a clone first. The
`main` branch pins the latest release instead and needs none of this.

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

Both resolve `souther-compiler` from `~/.m2`, so the `mvn install` above is what they need too.

CI does not run on this branch — it cannot resolve the SNAPSHOT — so the three commands above are
the check. On `main` CI runs all three against the released compiler.

The version is written in four places — the root pom, `account/deps.edn`, `issuetracker/build.gradle.kts`
and the annotation-processor snippet above. `bin/set-version.sh <version>` moves all four, and
`bin/check-version-consistency.sh` fails if they disagree.

The `souther` CLI, which `shippingfee/README.md` runs, is not published to Maven Central either. The
clone above builds it: `souther-cli/target/souther`.

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

### Compiling the model in a Kotlin Gradle build

The whole of it is the plugin and the version it compiles with:

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    id("org.souther-lang.souther") version "0.1.0"
}

souther {
    southerVersion = "0.1.0-SNAPSHOT"
}
```

`compileSouther` writes into the `main` source set, so the generated classes reach kotlinc, the test
compile class path and the jar. The plugin also adds `souther-runtime` at the version that compiled
the model, which is why this build declares no runtime dependency of its own.

Wiring this by hand took about forty lines here, and three of them were load-bearing in a way nothing
reported. The Kotlin plugin compiles Kotlin before Java within a source set, which is the wrong way
round when everything Kotlin depends on generated bytecode — so the processor needed a source set of
its own, and `implementation(souther.output)` was what ordered the two. A `.sou` is not a javac
source, so without `inputs.dir` editing `issues.sou` left the task `UP-TO-DATE` and the previously
generated classes in place. And the jar had to be told to take the other source set's output, or it
was empty. All three builds succeeded while being wrong.

Three smaller things the build still pins down: `jvmTarget` is `21`, because kotlinc still defaults
to 1.8 and 21 is Souther's runtime floor, so the boundary asks no more of a JVM than the generated
code it drives; `bootJar` is disabled so the artifact stays a plain jar, as the Maven examples' are;
and `settings.gradle.kts` lists `mavenLocal()` first, since `souther.version` is a `-SNAPSHOT`
published nowhere but `~/.m2` — the plugin resolves the compiler from the repositories the project
already declares.

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

There is no Kotlin-side support file, and that is the point: nothing has to be installed between Kotlin
and what Souther generates. A decoder is called as `IssueId.decoder().decode(id)` and answers Raoh's
`Result`, which is sealed, so `when (…) { is Ok -> …; is Err -> … }` is checked the way the case unions
are. What is left over is two conveniences that are private to the single file that uses each — an
`operator invoke` in the controller so a bound behavior reads as `attachLabel(request)`, and an
`Option.orNull()` in `IssueRows.kt` for the one nullable column — rather than a package suggesting that
using Souther needs one.

| Kotlin file | Role |
| --- | --- |
| `build.gradle.kts` | The build. `souther-gradle-plugin` compiles the model, and what is left is the Kotlin and Spring wiring (above) |
| `IssueRows.kt` | The one place that knows the issue tables. An issue spans `issues` and its `issue_labels` rows, so reading one produces the Map `Issue.decoder()` takes (labels as a list → a `Set` on decode; an absent assignee left out of the Map → `None`). Reading values out of a domain value is plain property access (`issue.id.value`), since a generated data is a record — construction is the guarded direction, not reading. A private `Option.orNull()` at the bottom is the one place a Souther optional meets a nullable column |
| `JooqIssueStore.kt` | The three injected implementations, each **extending** the generated abstract base. A Kotlin subclass reaches the base's `protected` factories, so the unit case is built with the inherited `IssueNotFound()`; values read out of storage go through the public `decoder()`, which re-checks their invariants — on this service's own writing rather than a caller's input, so a refusal there is `getOrThrow` and a 500, not a 400. SQL exceptions are not caught |
| `web/IssueTrackerConfig.kt` | The generated-side beans: the injected implementations, `AttachLabel.bind(...)` and friends, the pure behaviors' `of()`, and a jOOQ `Settings` that turns identifier quoting off. DataSource / DSLContext / TransactionManager come from autoconfig |
| `web/IssueController.kt` | `@RestController`. Every route is decode → one behavior → fold the output union into a status and a body, and the decode is a `when` over `Ok`/`Err` beside the `when` over the union. `shared-labels` reads two ids and combines them with `Result.map2`, so a call that got both wrong is told about both. `@Transactional` on the read-modify-write routes, so a concurrent call cannot drop a label by writing back a set it read too early |
| `web/BoardQuery.kt` | The read side. `countByLabel` / `topLabels` / `busyLabels` are pure behaviors over a whole `Board`, and a summary makes no decision the domain needs to be in on, so this is not an injected behavior: the boundary reads the rows and builds the `Board` through the derived decoder, with `getOrThrow` because those rows are this service's own writing |
| `web/BoundaryErrors.kt` | The one failure that is not a value. A rejected input is a `Result` the controller answers with, so what is left is a `DataAccessException` that passed through Souther — 503 |

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
so it carries a single minimal `package-info.java` for the processor to have something to run over —
the last one in this repository, since the Maven and Gradle examples moved to the build plugins.
`souther-clj` drives javac itself and takes that directory as its `:trigger-dir`. **The Clojure app puts
that generated output (`target/classes`) straight on its classpath** (`target/classes` is in
`:paths` in `deps.edn`).

The `:gen` alias is the one place that must not see it, which is what its `:replace-paths` says. A
module the compiler is compiling may not also be on its path — one name cannot mean two modules — and
`target/classes` holds `example.account` from the run before, so generating a second time without
this fails on the module the first run wrote. `souther.build` is unaffected: it comes from the
`souther-clj` dependency, which `:replace-paths` does not touch.

### Implementing an injected behavior from Clojure — `proxy` + `decoder()`

The generated injected behaviors are **abstract base classes** (`CurrentBalance` implements
`Behavior<AccountNo, CurrentBalanceResult>`; `UpdateBalance` has `apply(AccountNo, Balance)`).
Clojure implements them with `proxy`. But a `proxy` cannot reach the base's `protected` factories,
so it builds the returned domain values (`Balance` / `Withdrawn` / `NoAccount`) through the
**public generated `decoder()`** — the sanctioned boundary path for turning outside values into
domain data, with data constructors staying non-public (spec 8.5 / 2.1). No gen-class, no AOT.

These interop patterns are packaged as a library of their own,
[`org.souther-lang/souther-clj`](https://github.com/souther-lang/souther-clj), which this example
depends on like any other. It refers to no domain type and works by reflection over whatever
generated classes the caller passes in, so it depends on Clojure and Raoh and on nothing of
Souther's:

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
clojure -X:test                                  # behavior+DB and Pedestal boundary tests (8 of them)
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
