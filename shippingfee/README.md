# What the model already knows about your tests

A demo for people who write tests. It takes one page of a shipping-fee regulation, writes it down
as a model, and then asks the compiler what test cases that regulation implies — the equivalence
classes, the boundaries, the combinations, and the paths through the rules. Every number and every
block of output below was produced by running the commands shown, over the files in this directory.

The regulation, as it is written:

> 1. 注文番号は 4桁-6桁。
> 2. プレミアム会員の送料は無料とする。
> 3. 商品合計が 5,000 円以上のとき送料は無料とする。ただし離島宛は対象外とする。
> 4. 上記以外の送料は、本州 500 円、北海道・沖縄 800 円、離島 1,500 円とする。

`shippingfee.sou` is that page transcribed — four rules, about fifty lines. Nothing about testing is
written in it.

## Three rows, and what is missing from them

Someone who reads the regulation and writes the obvious cases writes about three: premium is free,
5,000 yen is free, just under it is not. One per clause, which reads like enough.

```sh
souther examples src/main/souther/*.sou
```

(`souther` is the CLI; the root README says where to get it.)

```
example.shippingfee                                      measurement: complete
  送料を求める             implemented   rows 3    pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 3   equivalence partitions 5/7
      ! no row is in `北海道沖縄` at 注文.地域
      ! no row is in `離島` at 注文.地域
      · 注文.地域 holds 3 classes and this behavior's rules compose 0 of them
      · 注文.会員 holds 2 classes and this behavior's rules compose 0 of them
      · no line: invariant 注文番号 #1 — it restricts this position to the values it admits, and no other value can be built here, about `注文.番号`
    combination pairs 7 covered, 9 uncovered
      ! no row is in `注文.合計/0 <= x < 5000` at 送料を求める/注文.合計 with `北海道沖縄` at 送料を求める/注文.地域
      ! no row is in `注文.合計/0 <= x < 5000` at 送料を求める/注文.合計 with `離島` at 送料を求める/注文.地域
      ! no row is in `注文.合計/5000 <= x <= 1000000` at 送料を求める/注文.合計 with `北海道沖縄` at 送料を求める/注文.地域
      ! no row is in `注文.合計/5000 <= x <= 1000000` at 送料を求める/注文.合計 with `離島` at 送料を求める/注文.地域
      ! no row is in `プレミアム` at 送料を求める/注文.会員 with `注文.合計/5000 <= x <= 1000000` at 送料を求める/注文.合計
      ! no row is in `一般` at 送料を求める/注文.会員 with `北海道沖縄` at 送料を求める/注文.地域
      ! no row is in `プレミアム` at 送料を求める/注文.会員 with `北海道沖縄` at 送料を求める/注文.地域
      ! no row is in `一般` at 送料を求める/注文.会員 with `離島` at 送料を求める/注文.地域
      ! no row is in `プレミアム` at 送料を求める/注文.会員 with `離島` at 送料を求める/注文.地域
    border      borders 3   obligations 4/6
      ! no row is at an IN point (comparison@48:23)
          · read as 送料を求める/注文.合計: in 0 <= 注文.合計 < 4999
      ! no row is at an OUT point (comparison@48:23)
          · read as 送料を求める/注文.合計: in 5000 < 注文.合計 <= 1000000
      · no OFF point is owed at 注文.合計 = 0 (invariant 商品合計 #1): excluded — the rules leave no value there
      · no OUT point is owed at 注文.合計 = 0 (invariant 商品合計 #1): excluded — the rules leave no value there
      · no OFF point is owed at 注文.合計 = 1000000 (invariant 商品合計 #1): excluded — the rules leave no value there
      · no OUT point is owed at 注文.合計 = 1000000 (invariant 商品合計 #1): excluded — the rules leave no value there
    branch      6/10
      ! no row goes through `case 北海道沖縄` (59:5)
      ! no row goes through `case 離島` (59:5)
      ! no row goes through `case 離島` (53:5)
      ! no row goes through `case 北海道沖縄` (53:5)
    decision    rules 7   taken 3
      ! no row takes a decision rule
          · it goes through `case 一般` (42:5)
          · the comparison at 48:23 holds
          · it goes through `case 北海道沖縄` (59:5)
      ! no row takes a decision rule
          · it goes through `case 一般` (42:5)
          · the comparison at 48:23 holds
          · it goes through `case 離島` (59:5)
      ! no row takes a decision rule
          · it goes through `case 一般` (42:5)
          · the comparison at 48:23 does not hold
          · it goes through `case 離島` (53:5)
      ! no row takes a decision rule
          · it goes through `case 一般` (42:5)
          · the comparison at 48:23 does not hold
          · it goes through `case 北海道沖縄` (53:5)
  declarations   obligations 0/2
  商品合計
      ! no row is at the ON point value = 0 (invariant 商品合計 #1)
      ! no row is at the ON point value = 1000000 (invariant 商品合計 #1)

1 behavior: 1 implemented, 0 unimplemented, 0 injected; 0 rows waiting for a `let`.
adequacy: not satisfied
23 gaps marked `!`: what a strict build refuses over.
```

None of that came from a coverage tool watching a test run. The classes are the ones the model's own
types name, the boundaries are the ones its `invariant`s and `guard`s state, and the paths are the
arms of its own `match`. Nobody decided that 北海道沖縄 is a case worth testing; the regulation did,
by naming it.

`no line: invariant 注文番号 #1` is the report declining to guess. What the model says about an order
number is `matches("[0-9]{4}-[0-9]{6}", value)` — a format rather than a set of classes — so there is
nothing there to divide the field into, and the position is named rather than counted as covered.

The last two are not under the behavior, because they are not about it. `商品合計` says a total is
between 0 and 1,000,000, and whether a row stands at either end is a question about that data — asked
once, wherever the rows that answer it are. What is under `送料を求める` is what its own rules draw:
the guard at 5,000 and the arms of its `match`.

The `!` marks are the subset a `--strict` build fails over, and the count at the end is of those.

The two words at the ends are different answers. `measurement: complete` says every measure could be
made; `adequacy: not satisfied` says what was measured leaves a gap. Here both are true at once —
everything was looked at, and this is what nothing covers.

## The rows it hands back

```sh
souther examples --generate src/main/souther/*.sou
```

The report comes out first and the rows follow it; the block below is the rows.

```
// generated by `souther examples --generate`: 8 rows to fill what nothing covers.
// Replace each `<?>` with what the system actually answers.
example 送料を求める
// fills 注文.地域=北海道沖縄
// fills case 北海道沖縄
// fills 注文.合計=0 <= x < 5000
// fills 注文.会員=一般
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(0), 地域 = 北海道沖縄, 会員 = 一般 })
        -> <?>
// fills 注文.地域=離島
// fills case 離島
// fills 注文.合計=0 <= x < 5000
// fills 注文.会員=一般
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(0), 地域 = 離島, 会員 = 一般 }) -> <?>
// fills case 離島
// fills 注文.合計=5000 <= x <= 1000000
// fills 注文.地域=離島
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(5000), 地域 = 離島, 会員 = 一般 })
        -> <?>
// fills case 北海道沖縄
// fills 注文.合計=5000 <= x <= 1000000
// fills 注文.地域=北海道沖縄
    | (
        注文 {
            番号 = 注文番号("0000-000000"),
            合計 = 商品合計(5000),
            地域 = 北海道沖縄,
            会員 = 一般
        }
    )
        -> <?>
// fills 注文.会員=プレミアム
// fills 注文.合計=5000 <= x <= 1000000
    | (
        注文 {
            番号 = 注文番号("0000-000000"),
            合計 = 商品合計(5000),
            地域 = 本州,
            会員 = プレミアム
        }
    )
        -> <?>
// fills 注文.会員=プレミアム
// fills 注文.地域=北海道沖縄
    | (
        注文 {
            番号 = 注文番号("0000-000000"),
            合計 = 商品合計(0),
            地域 = 北海道沖縄,
            会員 = プレミアム
        }
    )
        -> <?>
// fills 注文.会員=プレミアム
// fills 注文.地域=離島
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(0), 地域 = 離島, 会員 = プレミアム })
        -> <?>
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(1000000), 地域 = 本州, 会員 = 一般 })
        -> <?>
```

Eight rows. The `fills` lines say what a row is there for, and no row in the batch is one another row
makes unnecessary — the interior of the guard's range is stood in by the rows at 0, so nothing is
offered for it separately.

The inputs are written for you; the `<?>` is not, and never will be. What the system should answer is
the one thing a model cannot derive from itself, and a tool that filled it in would be writing a test
that passes by construction.

`注文番号("0000-000000")` is read out of the format rule the model states. Until the compiler could
do that, this whole block was empty — every combination needed an order number, no order number
could be written, and so the report said this instead:

```
// no row for `注文.地域=北海道沖縄 x 注文.会員=一般` in `送料を求める`: every value tried was refused at construction, which does not make the combination impossible
```

Note what it did not say. Not "covered", and not "impossible" — a combination nothing could build a
value for is one nothing knows about, and it is reported as its own kind of answer.

## The clause that is left

Two of the eight matter most: the rows that fill `case 離島` and `case 北海道沖縄`, both at 5,000 yen.
They are the arms of `離島以外は無料`, which is the regulation's ただし書き — 5,000 yen or more, and
the destination is a remote island. The three obvious rows never reach that `match` at all, and it is
where the production bug is.

The generator writes those two inputs and stops there, which is the whole of what it can do. Whether
a remote island at 5,000 yen is still paid is a sentence somebody wrote in the regulation, not
arithmetic on the model. Write `送料無料` into that `<?>` and everything goes green: the row holds,
the arm is reached, and the report is satisfied over a model that now contradicts the page it was
transcribed from. The `<?>` is the one thing the report does not check, and that is why it does not
fill it in.

Fill the eight in and the behavior is at `branch 10/10`, and that is the state this directory is
committed in:

```
example.shippingfee                                      measurement: complete
  送料を求める             implemented   rows 11   pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 3   equivalence partitions 7/7
      · no line: invariant 注文番号 #1 — it restricts this position to the values it admits, and no other value can be built here, about `注文.番号`
    combination pairs 16/16
    border      borders 3   obligations 6/6
      · no OFF point is owed at 注文.合計 = 0 (invariant 商品合計 #1): excluded — the rules leave no value there
      · no OUT point is owed at 注文.合計 = 0 (invariant 商品合計 #1): excluded — the rules leave no value there
      · no OFF point is owed at 注文.合計 = 1000000 (invariant 商品合計 #1): excluded — the rules leave no value there
      · no OUT point is owed at 注文.合計 = 1000000 (invariant 商品合計 #1): excluded — the rules leave no value there
    branch      10/10
    decision    rules 7   taken 7
  declarations   obligations 2/2

1 behavior: 1 implemented, 0 unimplemented, 0 injected; 0 rows waiting for a `let`.
adequacy: satisfied
```

`combination pairs 16/16` says every pair of classes taken from two different positions has a row. The
four `·` lines under `border` are points the report does not owe a row, because `商品合計` admits no
value just outside 0 or 1,000,000.

## And when the regulation changes

Suppose 沖縄 becomes its own region at 1,200 yen. In the model that is one case added to a sum, and
the compiler then makes you handle it everywhere it is matched and rename it everywhere a row said
北海道沖縄. All of that is ordinary type checking, and when it is done the eleven rows compile and
pass again.

What is not ordinary is that they are now measurably short, and by exactly this much:

```
  送料を求める             implemented   rows 11   pending 0
    signature   out specified 2/2  observed 2/2  verified 2/2
    partition   axes 3   equivalence partitions 7/8
      ! no row is in `沖縄` at 注文.地域
      · 注文.地域 holds 4 classes and this behavior's rules compose 0 of them
      · 注文.会員 holds 2 classes and this behavior's rules compose 0 of them
      · no line: invariant 注文番号 #1 — it restricts this position to the values it admits, and no other value can be built here, about `注文.番号`
    combination pairs 16 covered, 4 uncovered
      ! no row is in `注文.合計/0 <= x < 5000` at 送料を求める/注文.合計 with `沖縄` at 送料を求める/注文.地域
      ! no row is in `注文.合計/5000 <= x <= 1000000` at 送料を求める/注文.合計 with `沖縄` at 送料を求める/注文.地域
      ! no row is in `一般` at 送料を求める/注文.会員 with `沖縄` at 送料を求める/注文.地域
      ! no row is in `プレミアム` at 送料を求める/注文.会員 with `沖縄` at 送料を求める/注文.地域
    border      borders 3   obligations 6/6
    branch      10/12
      ! no row goes through `case 沖縄` (61:5)
      ! no row goes through `case 沖縄` (54:5)
    decision    rules 9   taken 7
      ! no row takes a decision rule
          · it goes through `case 一般` (43:5)
          · the comparison at 49:23 holds
          · it goes through `case 沖縄` (61:5)
      ! no row takes a decision rule
          · it goes through `case 一般` (43:5)
          · the comparison at 49:23 does not hold
          · it goes through `case 沖縄` (54:5)
  declarations   obligations 2/2

1 behavior: 1 implemented, 0 unimplemented, 0 injected; 0 rows waiting for a `let`.
adequacy: not satisfied
9 gaps marked `!`: what a strict build refuses over.
```

```
// generated by `souther examples --generate`: 3 rows to fill what nothing covers.
// Replace each `<?>` with what the system actually answers.
example 送料を求める
// fills 注文.地域=沖縄
// fills case 沖縄
// fills 注文.合計=0 <= x < 5000
// fills 注文.会員=一般
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(0), 地域 = 沖縄, 会員 = 一般 }) -> <?>
// fills case 沖縄
// fills 注文.合計=5000 <= x <= 1000000
// fills 注文.地域=沖縄
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(5000), 地域 = 沖縄, 会員 = 一般 })
        -> <?>
// fills 注文.会員=プレミアム
// fills 注文.地域=沖縄
    | (注文 { 番号 = 注文番号("0000-000000"), 合計 = 商品合計(0), 地域 = 沖縄, 会員 = プレミアム })
        -> <?>
```

The second is the same input at 5,000 yen, where the free-shipping clause is the one that decides, and the third is a premium member there.

A suite that goes green again after a specification change is the normal outcome, and it is the
dangerous one: nothing failed, so nothing says the regulation grew a case the tests do not reach.
Here it goes green and the report says it is short. Those are different sentences, and the second
one is the one worth having.

## What it will not say

The measurements are three-valued. A measure may report that something was found from part of the
rows; it may not report that something was *not* found unless every row was read. If a row times
out, or a value is too large to observe, the affected measure comes back `partial` and names nothing
— because "no row covers this" is a claim about all of the rows.

So a green report is a claim the compiler is willing to make about the model as written. It is not a
claim that the regulation is right, that the model matches it, or that the answers in the `<?>`
slots were correct. Those are still yours.

## Running it

```sh
mvn -o -pl shippingfee -am clean install            # generate types, check the rows, run the JUnit test
souther examples src/main/souther/*.sou             # the report above
souther examples --strict src/main/souther/*.sou    # and exit non-zero on any gap it names
```

The `example` rows are checked when the sources are compiled, so a row that stops holding fails the
build rather than a test run — `E1905`, naming the row (`souther doc E1905` says what the rule is).
Note the `clean`: an incremental build that does not recompile the sources does not recheck the rows
either.

A row that stops holding is not the same as a rule nothing reaches, and only the first fails a
compile. `--strict` is what makes the second fail too: this directory is at `adequacy: satisfied`, so
it exits zero today, and adding a region to the model without adding rows for it is what would turn
it non-zero.

`src/test/java` holds the part the rows do not reach — an order arriving as a map from outside,
decoded through the same format rule.
