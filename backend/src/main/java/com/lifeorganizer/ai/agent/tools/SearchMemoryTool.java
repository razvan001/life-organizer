package com.lifeorganizer.ai.agent.tools;

import com.lifeorganizer.ai.agent.TurnContext;
import com.lifeorganizer.memory.search.MemorySearch;
import com.lifeorganizer.memory.store.MemoryHit;
import com.lifeorganizer.memory.store.SourceType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SearchMemoryTool implements AgentTool {

    static final int DEFAULT_K = 5;
    static final int MAX_K = 8;
    static final int MAX_QUERY = 500;
    static final int MAX_TEXT = 300;

    private final MemorySearch memorySearch;

    public SearchMemoryTool(MemorySearch memorySearch) {
        this.memorySearch = memorySearch;
    }

    @Override
    public String name() {
        return "search_memory";
    }

    @Override
    public String description() {
        return "Semantic search over the user's own notes and one-line summaries of past days (what was done, "
            + "postponed or cancelled, and why). Use it when the user mentions how they feel, what usually works "
            + "for them, or past days.";
    }

    @Override
    public String parametersJson() {
        return """
            {"type":"object","properties":{
              "query":{"type":"string","description":"what to look for, in natural language"},
              "k":{"type":"integer","minimum":1,"maximum":8,"description":"how many results (default 5)"},
              "source":{"type":"string","enum":["NOTE","DAY"],"description":"only notes or only past days"}
            },"required":["query"],"additionalProperties":false}
            """;
    }

    @Override
    public String execute(JsonNode arguments, TurnContext context) {
        String query = ToolJson.requiredText(arguments, "query", MAX_QUERY);
        List<MemoryHit> hits = memorySearch.search(query, k(arguments), source(arguments));
        if (hits.isEmpty()) {
            return "No matching notes or past days.";
        }
        return ToolJson.write(hits.stream().map(SearchMemoryTool::describe).toList());
    }

    private static int k(JsonNode arguments) {
        JsonNode node = arguments.path("k");
        if (node.isMissingNode() || node.isNull()) {
            return DEFAULT_K;
        }
        if (!node.isIntegralNumber() || node.asInt() < 1 || node.asInt() > MAX_K) {
            throw new ToolException("k must be a whole number between 1 and " + MAX_K);
        }
        return node.asInt();
    }

    private static SourceType source(JsonNode arguments) {
        String value = ToolJson.optionalText(arguments, "source", 10);
        if (value == null) {
            return null;
        }
        try {
            return SourceType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("source must be NOTE or DAY");
        }
    }

    private static Map<String, Object> describe(MemoryHit hit) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("source", hit.sourceType().name());
        json.put("title", hit.title());
        json.put("date", hit.sourceDate() == null ? null : hit.sourceDate().toString());
        json.put("text", hit.text().length() <= MAX_TEXT ? hit.text() : hit.text().substring(0, MAX_TEXT) + "...");
        json.put("score", Math.round(hit.score() * 100) / 100.0);
        return json;
    }
}
