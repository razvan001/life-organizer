package com.lifeorganizer.memory.search;

/** date is ISO yyyy-MM-dd for DAY results and null for notes. */
public record MemoryResultDto(String sourceType, String sourceId, String title, String date, String snippet,
                              double score) {
}
