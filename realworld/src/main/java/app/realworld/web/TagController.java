// The tags any article carries. The one endpoint in the API that asks nobody who is reading.
package app.realworld.web;

import example.articles.ReadTags;
import example.articles.Tag;
import example.articles.TagList;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class TagController {

    private final ReadTags readTags;

    public TagController(ReadTags readTags) {
        this.readTags = readTags;
    }

    /** {@code GET /api/tags}. No auth, no viewer, and nothing to reconcile — the shapes already agree. */
    @GetMapping("/api/tags")
    public Map<String, Object> tags() {
        TagList tags = readTags.apply();
        return ConduitJson.envelope("tags",
                tags.tags().stream().map(Tag.encoder()::encode).toList());
    }
}
