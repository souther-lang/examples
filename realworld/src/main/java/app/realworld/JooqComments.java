// The jOOQ implementations of comments.sou's injected behaviors.
package app.realworld;

import blog.articles.Slug;
import blog.comments.Comment;
import blog.comments.CommentBody;
import blog.comments.CommentId;
import blog.comments.CommentThread;
import blog.comments.FindComment;
import blog.comments.FindCommentResult;
import blog.comments.ReadComments;
import blog.comments.RemoveComment;
import blog.comments.Removed;
import blog.comments.StoreComment;
import blog.identity.Profile;


import org.jooq.DSLContext;
import org.jooq.Record;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

public final class JooqComments {

    private JooqComments() {
    }

    /** storeComment: the row, and the comment as stored — with the id the store handed out. */
    public static final class Store extends StoreComment {

        private final DSLContext dsl;

        public Store(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Comment apply(Slug slug, CommentBody body, Profile author, LocalDateTime at) {
            Map<String, Object> profile = Profile.encoder().encode(author);
            Integer id = dsl.insertInto(table(name("comments")))
                    .columns(field(name("slug"), String.class),
                            field(name("body"), String.class),
                            field(name("author"), String.class),
                            field(name("created_at"), LocalDateTime.class),
                            field(name("updated_at"), LocalDateTime.class))
                    .values(Slug.encoder().encode(slug),
                            CommentBody.encoder().encode(body),
                            (String) profile.get("username"),
                            at, at)
                    .returning(field(name("id"), Integer.class))
                    .fetchOne(field(name("id"), Integer.class));

            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("id", id);
            raw.put("body", CommentBody.encoder().encode(body));
            raw.put("author", profile);
            raw.put("createdAt", at.toString());
            raw.put("updatedAt", at.toString());
            return Comment.decoder().decode(raw).getOrThrow();
        }
    }

    /** readComments: the thread on an article, oldest first, each with its author's profile. */
    public static final class ReadThread extends ReadComments {

        private final DSLContext dsl;

        public ReadThread(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public CommentThread apply(Slug slug) {
            List<Map<String, Object>> comments = selectComments(dsl)
                    .where(field(name("c", "slug"), String.class)
                            .eq(Slug.encoder().encode(slug)))
                    .orderBy(field(name("c", "id")).asc())
                    .fetch()
                    .map(JooqComments::commentMap);
            return CommentThread.decoder()
                    .decode(Map.of("comments", comments)).getOrThrow();
        }
    }

    /** findComment: the comment behind an id, or the case that says there is none. */
    public static final class Find extends FindComment {

        private final DSLContext dsl;

        public Find(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public FindCommentResult apply(CommentId id) {
            Record row = selectComments(dsl)
                    .where(field(name("c", "id"), Integer.class)
                            .eq((int) id.value()))
                    .fetchOne();
            return row == null
                    ? CommentNotFound()
                    : Comment.decoder().decode(commentMap(row)).getOrThrow();
        }
    }

    /** removeComment: the row goes. */
    public static final class Remove extends RemoveComment {

        private final DSLContext dsl;

        public Remove(DSLContext dsl) {
            this.dsl = dsl;
        }

        @Override
        public Removed apply(Comment comment) {
            dsl.deleteFrom(table(name("comments")))
                    .where(field(name("id"), Integer.class).eq((int) comment.id().value()))
                    .execute();
            return Removed();
        }
    }

    // --- the shared read ---

    private static org.jooq.SelectOnConditionStep<? extends Record> selectComments(DSLContext dsl) {
        return dsl.select(field(name("c", "id"), Integer.class).as("id"),
                        field(name("c", "body"), String.class).as("body"),
                        field(name("c", "created_at"), LocalDateTime.class).as("created_at"),
                        field(name("c", "updated_at"), LocalDateTime.class).as("updated_at"),
                        field(name("u", "username"), String.class).as("author_username"),
                        field(name("u", "bio"), String.class).as("author_bio"),
                        field(name("u", "image"), String.class).as("author_image"))
                .from(table(name("comments")).as("c"))
                .join(table(name("users")).as("u"))
                .on(field(name("c", "author"), String.class)
                        .eq(field(name("u", "username"), String.class)));
    }

    private static Map<String, Object> commentMap(Record row) {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("username", row.get("author_username", String.class));
        putIfPresent(author, "bio", row.get("author_bio", String.class));
        putIfPresent(author, "image", row.get("author_image", String.class));

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", row.get("id", Integer.class));
        raw.put("body", row.get("body", String.class));
        raw.put("author", author);
        raw.put("createdAt", row.get("created_at", LocalDateTime.class).toString());
        raw.put("updatedAt", row.get("updated_at", LocalDateTime.class).toString());
        return raw;
    }

    private static void putIfPresent(Map<String, Object> raw, String key, String value) {
        if (value != null) {
            raw.put(key, value);
        }
    }
}
