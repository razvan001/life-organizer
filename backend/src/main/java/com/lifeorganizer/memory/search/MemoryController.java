package com.lifeorganizer.memory.search;

import com.lifeorganizer.error.InvalidRequestException;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    static final int MAX_QUERY_LENGTH = 500;
    static final int DEFAULT_K = 8;
    static final int MAX_K = 20;
    static final int SNIPPET_LENGTH = 240;

    private final MemorySearch search;

    public MemoryController(MemorySearch search) {
        this.search = search;
    }

    @GetMapping("/search")
    public List<MemoryResultDto> search(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) Integer k,
                                        @RequestParam(required = false) String source) {
        if (q == null || q.isBlank()) {
            throw new InvalidRequestException("q is required");
        }
        if (q.length() > MAX_QUERY_LENGTH) {
            throw new InvalidRequestException("q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        int limit = k == null ? DEFAULT_K : k;
        if (limit < 1 || limit > MAX_K) {
            throw new InvalidRequestException("k must be between 1 and " + MAX_K);
        }
        return search.search(q.trim(), limit, sourceType(source)).stream().map(MemoryController::toDto).toList();
    }

    private static SourceType sourceType(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            return SourceType.valueOf(source);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("source must be NOTE or DAY");
        }
    }

    private static MemoryResultDto toDto(MemoryHit hit) {
        String text = hit.text();
        String snippet = text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH) + "…";
        return new MemoryResultDto(hit.sourceType().name(), hit.sourceId(), hit.title(),
            hit.sourceDate() == null ? null : hit.sourceDate().toString(), snippet, hit.score());
    }
}
