// Where the domain's JSON and the API's JSON are reconciled.
//
// Every other example in this repository lets the derived encoder's output be the response: the
// shape of the JSON is the shape of the data, because both are the module's own decision. Here the
// shape was decided by somebody else — by the RealWorld spec and the frontends written against it —
// and three differences have to be paid for. They are paid here, once, rather than in each handler.
//
//   1. A response carries facts about the request, not only about the value. Whether the viewer
//      follows this author or has favorited this article is not a property of the author or the
//      article, so no .sou holds it and it is put in on the way out.
//   2. A derived encoder omits an optional field rather than writing null. The spec writes
//      "bio": null, and a frontend reading obj.bio.length on an absent key is a frontend that
//      breaks against an otherwise correct backend, so the absent keys are filled with null.
//   3. Souther's DateTime is a LocalDateTime and encodes as its toString, which has no zone and
//      drops the fractional part when it is zero. The spec's timestamps are UTC with exactly three
//      fractional digits.
package app.realworld.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConduitJson {

    /** The spec's timestamp: UTC, always three fractional digits, always a Z. */
    private static final DateTimeFormatter SPEC_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'");

    private ConduitJson() {
    }

    /** Every RealWorld response is wrapped in a single key naming what it holds. */
    public static Map<String, Object> envelope(String key, Object body) {
        return Map.of(key, body);
    }

    /**
     * The other side of {@link #envelope}: a request body is wrapped in the same single key, and this
     * is what is inside it. A body that is absent, is not an object, or does not carry the key at all
     * yields an empty object rather than a null — what the request failed to send is the decoder's to
     * refuse, field by field, not this method's to refuse wholesale.
     *
     * <p>The node returned is the request's own, so a caller that adds fields to it (the boundary
     * supplying an author or a timestamp) copies it first.
     */
    public static ObjectNode inside(JsonNode body, String key) {
        JsonNode nested = body == null ? null : body.get(key);
        return nested instanceof ObjectNode object ? object : JsonNodeFactory.instance.objectNode();
    }

    /**
     * A profile as the spec states it. {@code following} is the viewer's relationship to this person
     * and comes from the followee set the boundary read once for the whole request.
     */
    public static Map<String, Object> profile(Map<String, Object> encodedProfile, boolean following) {
        Map<String, Object> out = withNullableProfileFields(encodedProfile);
        out.remove("email");                     // a profile is public; a user's address is not
        out.put("following", following);
        return out;
    }

    /**
     * The authenticated user as the spec states it: the profile fields, the address only this user
     * sees, and the token the boundary just issued. The token is not a domain value and no .sou
     * names one, so this is the only place it joins the response.
     */
    public static Map<String, Object> user(Map<String, Object> encodedUser, String token) {
        Map<String, Object> out = withNullableProfileFields(encodedUser);
        out.put("token", token);
        return out;
    }

    /**
     * An article or a summary as the spec states it. All three of this class's reasons to exist meet
     * here: the two flags and the author's are facts about the request, the timestamps are rewritten,
     * and the nested author gets its absent optionals back.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> article(Map<String, Object> encodedArticle,
                                              boolean favorited,
                                              int favoritesCount,
                                              boolean authorFollowed) {
        Map<String, Object> out = new LinkedHashMap<>(encodedArticle);
        out.put("createdAt", timestamp(out.get("createdAt")));
        out.put("updatedAt", timestamp(out.get("updatedAt")));
        out.put("author", profile((Map<String, Object>) out.get("author"), authorFollowed));
        out.put("favorited", favorited);
        out.put("favoritesCount", favoritesCount);
        return out;
    }

    /**
     * A comment as the spec states it: the same author block an article carries, following and all,
     * and the same rewritten timestamps.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> comment(Map<String, Object> encodedComment, boolean authorFollowed) {
        Map<String, Object> out = new LinkedHashMap<>(encodedComment);
        out.put("createdAt", timestamp(out.get("createdAt")));
        out.put("updatedAt", timestamp(out.get("updatedAt")));
        out.put("author", profile((Map<String, Object>) out.get("author"), authorFollowed));
        return out;
    }

    /**
     * Rewrites Souther's rendering of a DateTime into the spec's. Souther's DateTime is a
     * LocalDateTime, so what arrives has no zone and may have no fractional part at all.
     */
    public static String timestamp(Object encodedDateTime) {
        return LocalDateTime.parse((String) encodedDateTime).format(SPEC_TIMESTAMP);
    }

    /**
     * The optional fields the spec writes as null. A derived encoder leaves the key out when the
     * value is None, and `undefined` is not what a frontend reading these was written against.
     */
    private static Map<String, Object> withNullableProfileFields(Map<String, Object> encoded) {
        Map<String, Object> out = new LinkedHashMap<>(encoded);
        out.putIfAbsent("bio", null);
        out.putIfAbsent("image", null);
        return out;
    }
}
