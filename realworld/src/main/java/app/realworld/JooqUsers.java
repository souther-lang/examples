// The jOOQ implementations of identity.sou's injected behaviors. They are nested in one class
// because they are one table's worth of SQL: the users and follows rows change together, and a
// column added here has to be read by more than one of them.
//
// Each nested class extends the generated abstract base, so the implementation is supplied to the
// domain rather than called by it. A value read out of the database is built through the public
// derived decoder, which re-checks the invariants on data that has been sitting in storage; the
// unit case UserNotFound is built through the protected factory the base inherits, because a data
// constructor is not public and a behavior may only build the cases it declared.
//
// SQL exceptions are not caught. A database outage is not a domain outcome, so it is not a case:
// the exception passes through Souther untouched and the boundary maps it to 503 (ADR-0029).
package app.realworld;

import example.identity.Credentialed;
import example.identity.Email;
import example.identity.FindLogin;
import example.identity.FindLoginResult;
import example.identity.FindUserByEmail;
import example.identity.FindUserByEmailResult;
import example.identity.FindUserByName;
import example.identity.FindUserByNameResult;
import example.identity.Followees;
import example.identity.PasswordHash;
import example.identity.ReadFollowees;
import example.identity.StoreFollow;
import example.identity.StorePassword;
import example.identity.StoreUnfollow;
import example.identity.StoreUser;
import example.identity.StoreUserUpdate;
import example.identity.User;
import example.identity.Username;

import net.unit8.raoh.Err;
import net.unit8.raoh.Ok;
import net.unit8.raoh.Path;
import net.unit8.raoh.Result;

import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

public final class JooqUsers {

    private JooqUsers() {
    }

    /** findUserByEmail: the registration check, and nothing else reads a user by address. */
    public static final class FindByEmail extends FindUserByEmail {

        private final DSLContext dsl;

        public FindByEmail(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FindUserByEmailResult apply(Email email) {
            Record row = userRow(dsl, "email", Email.encoder().encode(email));
            return row == null ? UserNotFound() : decodeUser(row);
        }
    }

    /** findUserByName: the other half of the registration check, and the profile lookup. */
    public static final class FindByName extends FindUserByName {

        private final DSLContext dsl;

        public FindByName(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FindUserByNameResult apply(Username username) {
            Record row = userRow(dsl, "username", Username.encoder().encode(username));
            return row == null ? UserNotFound() : decodeUser(row);
        }
    }

    /**
     * findLogin: the user and the hash to check a password against, read as one row because they are
     * read for one purpose. An address with no row is the same UserNotFound a wrong password becomes
     * at the boundary, so nothing here tells a caller which addresses have accounts.
     */
    public static final class FindLoginRow extends FindLogin {

        private final DSLContext dsl;

        public FindLoginRow(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FindLoginResult apply(Email email) {
            Record row = userRow(dsl, "email", Email.encoder().encode(email));
            if (row == null) {
                return UserNotFound();
            }
            Map<String, Object> raw = Map.of(
                    "user", userMap(row),
                    "hash", row.get(field(name("password_hash"), String.class)));
            return decodeOrThrow(Credentialed.decoder().decode(raw, Path.ROOT));
        }
    }

    /** storeUser: the new row, and the user as stored. */
    public static final class Store extends StoreUser {

        private final DSLContext dsl;

        public Store(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public User apply(User user, PasswordHash hash) {
            Map<String, Object> encoded = User.encoder().encode(user);
            dsl.insertInto(table(name("users")))
                    .columns(field(name("username"), String.class),
                            field(name("email"), String.class),
                            field(name("password_hash"), String.class),
                            field(name("bio"), String.class),
                            field(name("image"), String.class))
                    .values((String) encoded.get("username"),
                            (String) encoded.get("email"),
                            PasswordHash.encoder().encode(hash),
                            (String) encoded.get("bio"),
                            (String) encoded.get("image"))
                    .execute();
            return user;
        }
    }

    /** storeUserUpdate: the changed row, keyed by the username the user had before the change. */
    public static final class Update extends StoreUserUpdate {

        private final DSLContext dsl;

        public Update(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public User apply(Username previous, User user) {
            Map<String, Object> encoded = User.encoder().encode(user);
            dsl.update(table(name("users")))
                    .set(field(name("username"), String.class), (String) encoded.get("username"))
                    .set(field(name("email"), String.class), (String) encoded.get("email"))
                    .set(field(name("bio"), String.class), (String) encoded.get("bio"))
                    .set(field(name("image"), String.class), (String) encoded.get("image"))
                    .where(field(name("username"), String.class)
                            .eq(Username.encoder().encode(previous)))
                    .execute();
            return user;
        }
    }

    /** storePassword: the new hash, on its own, because a password is not part of a User. */
    public static final class SetPassword extends StorePassword {

        private final DSLContext dsl;

        public SetPassword(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public PasswordHash apply(Username username, PasswordHash hash) {
            dsl.update(table(name("users")))
                    .set(field(name("password_hash"), String.class),
                            PasswordHash.encoder().encode(hash))
                    .where(field(name("username"), String.class)
                            .eq(Username.encoder().encode(username)))
                    .execute();
            return hash;
        }
    }

    /**
     * readFollowees: everybody one viewer follows, in one query. The whole set is read at once
     * because the boundary decides `following` for a page of authors, and asking per author would be
     * one query per row on every listing.
     */
    public static final class ReadFollowing extends ReadFollowees {

        private final DSLContext dsl;

        public ReadFollowing(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Followees apply(Username viewer) {
            return followeesOf(dsl, Username.encoder().encode(viewer));
        }
    }

    /** storeFollow: following twice is the same row, so the insert is ignored when it is there. */
    public static final class Follow extends StoreFollow {

        private final DSLContext dsl;

        public Follow(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Followees apply(Username follower, Username followee) {
            String who = Username.encoder().encode(follower);
            dsl.insertInto(table(name("follows")))
                    .columns(field(name("follower"), String.class), field(name("followee"), String.class))
                    .values(who, Username.encoder().encode(followee))
                    .onConflictDoNothing()
                    .execute();
            return followeesOf(dsl, who);
        }
    }

    /** storeUnfollow: unfollowing somebody you never followed removes no row and is not an error. */
    public static final class Unfollow extends StoreUnfollow {

        private final DSLContext dsl;

        public Unfollow(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Followees apply(Username follower, Username followee) {
            String who = Username.encoder().encode(follower);
            dsl.deleteFrom(table(name("follows")))
                    .where(field(name("follower"), String.class).eq(who))
                    .and(field(name("followee"), String.class).eq(Username.encoder().encode(followee)))
                    .execute();
            return followeesOf(dsl, who);
        }
    }

    // --- the shared reads ---

    private static Record userRow(DSLContext dsl, String column, String value) {
        return dsl.select(field(name("username"), String.class),
                        field(name("email"), String.class),
                        field(name("password_hash"), String.class),
                        field(name("bio"), String.class),
                        field(name("image"), String.class))
                .from(table(name("users")))
                .where(field(name(column), String.class).eq(value))
                .fetchOne();
    }

    /**
     * A row as the neutral map the derived decoder reads. A null column is left out rather than
     * carried as a null: the domain's optional fields are absent-or-present, and `bio` missing is
     * what None decodes from.
     */
    private static Map<String, Object> userMap(Record row) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("username", row.get(field(name("username"), String.class)));
        raw.put("email", row.get(field(name("email"), String.class)));
        putIfPresent(raw, "bio", row.get(field(name("bio"), String.class)));
        putIfPresent(raw, "image", row.get(field(name("image"), String.class)));
        return raw;
    }

    private static void putIfPresent(Map<String, Object> raw, String key, String value) {
        if (value != null) {
            raw.put(key, value);
        }
    }

    private static User decodeUser(Record row) {
        return decodeOrThrow(User.decoder().decode(userMap(row), Path.ROOT));
    }

    private static Followees followeesOf(DSLContext dsl, String follower) {
        List<String> names = dsl.select(field(name("followee"), String.class))
                .from(table(name("follows")))
                .where(field(name("follower"), String.class).eq(follower))
                .fetch(field(name("followee"), String.class));
        return decodeOrThrow(Followees.decoder()
                .decode(Map.of("usernames", List.copyOf(names)), Path.ROOT));
    }

    /**
     * A stored row that no longer meets the domain's invariants is not a domain outcome — no case was
     * declared for it — so it is a platform failure, and the boundary answers 500 rather than dressing
     * corrupt storage up as a business answer.
     */
    private static <T> T decodeOrThrow(Result<T> result) {
        return switch (result) {
            case Ok<T> ok -> ok.value();
            case Err<T> err -> throw new IllegalStateException(
                    "a stored row no longer meets the domain's invariants: " + err.issues().asList());
        };
    }
}
