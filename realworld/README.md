# The RealWorld API, with the domain in Souther

[RealWorld](https://docs.realworld.show/) is a Medium clone specified once and implemented many
times, so that a frontend somebody else wrote runs against a backend you wrote. This module is one of
those backends. The domain is three `.sou` modules; the HTTP, the JWT, the bcrypt and the SQL are
Spring Boot and jOOQ around them.

## Running it

```sh
mvn -pl realworld spring-boot:run
```

It listens on 8080 against an in-memory H2, so the data goes when the process does. The JWT signing
key has a development default in `application.properties`; `REALWORLD_JWT_SECRET` overrides it.

## Pointing a frontend at it

Clone any of the frontend implementations the RealWorld list carries and set its API base URL to
`http://localhost:8080/api`. Where that setting lives differs — some read an environment variable,
some have a constant in the source — but that is the whole of the work, because the point of the
specification is that nothing else has to match.

Two details in the spec are the ones that make a conforming frontend fail against a backend that is
otherwise correct, and both have tests here:

- the scheme is `Authorization: Token <jwt>`, not `Bearer`
- `bio` and `image` are written as `null` rather than left out

## Checking it against the specification

The specification ships a Postman collection. It needs node, so it is not wired into `mvn verify`;
start the service and run it separately:

```sh
npx newman run https://raw.githubusercontent.com/gothinkster/realworld/main/api/Conduit.postman_collection.json \
  --env-var "APIURL=http://localhost:8080/api"
```

## Where the two JSONs differ

Every other example in this repository lets the derived encoder's output be the response. It can,
because the shape of the data and the shape of the JSON are both the module's own decision. Here they
are not: the shape was settled by the specification and by the frontends already written against it,
and `ConduitJson` is where the difference is paid. There are three of them.

**A response carries facts about the request.** Whether the viewer follows this author, or has
favorited this article, is not a property of the author or the article — the same article answers
differently to two callers. So no `.sou` holds those fields, and they are put in on the way out. What
the domain does hold is the reads they come from: the viewer's followee set and favorited set, each
read once for the whole response rather than once per row.

**A derived encoder omits an optional field rather than writing `null`.** The specification writes
`"bio": null`, and a frontend that reads `user.bio` was written against that, so the absent keys are
filled back in.

**Souther's `DateTime` is a `LocalDateTime` held to the second** and encodes as its `toString`, which
carries no zone and no fractional part. The specification's timestamps are `2016-02-18T03:22:56.637Z`,
so the boundary writes the zone and the milliseconds back in, and truncates its clock reading to a
second before the domain is handed it — a finer value is refused at the decoder rather than stored.

None of the three is a defect in the derived codecs. They are what it looks like when the JSON is
somebody else's decision, and the useful thing about this module is that all three are in one file
you can read in a minute.

## The three modules

`identity.sou` is who somebody is, how they prove it, and who they follow. `articles.sou` is what an
article is, how its slug is made, and who may change it. `comments.sou` hangs comments on articles.
The chain runs comments → articles → identity, and articles → identity, so the two paths to `Profile`
meet and agree.

They are `blog.identity`, `blog.articles` and `blog.comments` rather than `example.*` like the rest of
this repository, and the root is the point: what these three model is a blogging platform, and the
RealWorld API is one way to reach it. Naming them `app.realworld.*` would have put the domain under
the name of an API it does not know exists, and the whole shape of this module — the boundary
reconciling two JSONs — depends on that being true. `shared.money` names its root for the same kind of
reason.

Some things are worth pointing at:

- **A `User` does not carry its password hash.** A `User` is what a response is built from and a
  derived encoder writes every field it is given, so the hash travels beside it — `findLogin` answers
  a `Credentialed`, which never leaves the behavior that reads it.
- **A failed login answers `InvalidCredentials` whichever half was wrong.** An answer that told an
  unknown address apart from a wrong password would tell a stranger which addresses have accounts.
- **Authorization is a returned case.** `updateArticle`, `deleteArticle` and `deleteComment` each
  answer `NotTheAuthor`, and the boundary only chooses 403 for it. Nothing in the controllers tests an
  author.
- **So is a decoder's refusal.** A generated decoder answers raoh's `Result`, and a route switches on
  it beside the case union the behavior answers — `case Err(var issues)` next to `case SlugTaken _`.
  There is no boundary class between the two: `Slug.decoder().decode(slug)` is the whole of it, and
  what a route can reply is readable from the route. Turning the `Result` into an exception on the way
  in would have spent raoh's accumulation before it was used, and `PUT /api/user` is where that shows:
  the user and the password go through different decoders, so the two Results are combined and a
  request that broke both is told about both.
- **A search is a value.** `Limit` and `Offset` carry their bounds as invariants, so `?limit=1000` is
  refused by the decoder and the SQL is never shown it. The listing and the feed are two cases of a
  sum rather than one query with nullable fields, because a feed has no tag to filter by and a global
  list has no followees.
- **The shape of a module says which operations carry a decision.** Following, editing and deleting
  are composed behaviors, because each has a rule. Unfollowing, favoriting and commenting are injected
  writes the boundary calls directly, because they have none. A composed behavior that only forwarded
  its argument would state nothing.

## Holding the SQL to the model

Every other test in this module drives the API over HTTP and asserts in Java what came back. Two do
not. `ReadArticlesExamplesTest` and `SlugExistsDifferentialTest` bind the jOOQ implementation that
`RealWorldConfig` wires into the running application, and what decides them is written in the `.sou`
files beside the behaviors they are about.

```java
BoundExamples bound = SoutherExamples.of(MODEL).bind(new JooqArticles.ReadPage(dsl));
return bound.rows().stream().map(row -> dynamicTest(
        row.shown(), () -> assertTrue(bound.evaluate(row).held(), ...)));
```

One test per recorded row, named by the row. The only assertion is that the row held; when one does
not, what is printed is the compiler's own sentence about where the two values part. The source is
read when the test runs rather than travelling with the classes, so a model edited after the
implementation was compiled is found out here.

The same ten inputs are asked a second question, by a second factory:

```java
ContractObservation observed = bound.checkContract(row);
assertInstanceOf(ContractObservation.NoClauseWasBroken.class, observed, observed.shown());
```

`evaluate` holds the answer to the page somebody wrote out; `checkContract` holds it to the
behavior's `ensures` and to nothing the row records. `readArticles` states two things — that a page
never holds more than the limit asked for, and that every article in it matches the query it was
asked with — and the second is the whole of what the `where` underneath is for. That question keeps
its meaning in a world the rows were not recorded in, where the written page is no longer the answer
and the declaration still is. What it does not claim is that a clause bore on the answer:
`NoClauseWasBroken` is the absence of a violation, and the row filtering only on `favoritedBy` proves
nothing, because an ArticleSummary carries no favouriting for a clause to read.

`readArticles` is the behavior worth doing this to. Its input is a sum of two shapes carrying three
optional filters, so the `where` underneath is assembled differently for every combination — the code
a reader cannot check by reading. The ten rows in `articles.sou` are ten combinations, and each names
a way the assembly goes wrong while still reading correctly: a filter dropped, a total counted after
the limit, an offset applied without a tie-breaker, a feed of nobody shown everything.

What the rows are worth is measurable. `SlugExistsDifferentialTest` and the `or`-for-`and` factory in
`ReadArticlesExamplesTest` bind implementations with one thing changed, and the second is the sharper
of the two: combining the filters with `or` instead of `and` returns the union of what each matches,
and **seven of the ten rows still hold against it**. A tag alone cannot tell a union from an
intersection, an author alone cannot, and neither can a tag and an author that one article satisfies
together. What catches it is the row where the two filters disagree — the one whose result is empty,
which is the row nobody writing assertions by hand thinks to write, because an empty page looks like
nothing to assert about.

The declaration catches that same implementation on that same row, and catches it without being
shown a page anybody wrote: a union holds articles matching one filter and not the other, which is
what the clause refuses. So the row nobody thinks to write is one the model decides by itself — the
inputs are still somebody's, and the answer is not. On the other nine rows the two oracles agree
with each other, and the clause is blind where the rows are: a tag on its own cannot tell a union
from an intersection whichever question is asked.

`slugExists` is the other half. Its `fake` table exists so that `createArticle`'s rows have something
to dispatch to, but it is a statement about the real dependency all the same: that
`how-to-train-your-dragon` names no article and `taken` does. Nothing had ever checked it — ADR-0093
compares a fake with its behavior's recorded rows, and `slugExists` had none. It has two now, stating
the same inputs, and the test asks both questions of one implementation under one world:
`evaluate` adjudicates what the behavior owes, `observe` relates what the fake states to what the SQL
answered, and `alsoBy` ties an entry to the rows stating its input. Read the wrong column and both
move together while the two texts go on agreeing with each other, which is what says the
implementation is the one that moved.

The world is arranged by the test and not by the model, because the same entry reads `AsStated` under
one world and `OtherThanStated` under another. Nothing in the API can know that two of its calls saw
one world; the caller is the only thing that does.

One thing this does not yet do. `souther examples --generate` proposes rows for what nothing covers,
and it works — remove the two feed rows and it hands back a `FeedQuery` row to fill in. But it reads
`query` as a single axis of two cases and does not see the three optional filters inside
`GlobalQuery`, so the combinations that decide the `where` are not among what it proposes. The ten
rows were reasoned out by hand.
