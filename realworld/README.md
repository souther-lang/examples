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

**Souther's `DateTime` is a `LocalDateTime`** and encodes as its `toString`, which carries no zone
and drops the fractional part when it is zero. The specification's timestamps are
`2016-02-18T03:22:56.637Z`.

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
- **A search is a value.** `Limit` and `Offset` carry their bounds as invariants, so `?limit=1000` is
  refused by the decoder and the SQL is never shown it. The listing and the feed are two cases of a
  sum rather than one query with nullable fields, because a feed has no tag to filter by and a global
  list has no followees.
- **The shape of a module says which operations carry a decision.** Following, editing and deleting
  are composed behaviors, because each has a rule. Unfollowing, favoriting and commenting are injected
  writes the boundary calls directly, because they have none. A composed behavior that only forwarded
  its argument would state nothing.
