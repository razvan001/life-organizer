package com.lifeorganizer.memory.store;

import com.lifeorganizer.memory.embedding.TextEmbedder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The pgvector table behind memory search. Plain SQL on purpose: the vector operators are the point.
 * Vectors are sent as '[f1,f2,...]' strings and cast with ::vector, so no pgvector Java library is needed.
 */
@Component
public class MemoryChunkStore {

    private static final RowMapper<MemoryHit> HIT = (rs, row) -> {
        Date date = rs.getDate("source_date");
        return new MemoryHit(SourceType.valueOf(rs.getString("source_type")), rs.getString("source_id"),
            date == null ? null : date.toLocalDate(), rs.getString("title"), rs.getString("text"),
            rs.getDouble("score"));
    };

    private final JdbcTemplate jdbc;

    public MemoryChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Deletes every chunk of this source, then stores the given ones. */
    @Transactional
    public void replace(SourceType type, String sourceId, LocalDate sourceDate, String title, String contentHash,
                        List<NewChunk> chunks) {
        delete(type, sourceId);
        for (NewChunk chunk : chunks) {
            jdbc.update("""
                    insert into memory_chunk (source_type, source_id, source_date, title, text, content_hash, embedding)
                    values (?, ?, ?, ?, ?, ?, ?::vector)
                    """,
                type.name(), sourceId, sourceDate == null ? null : Date.valueOf(sourceDate), title, chunk.text(),
                contentHash, literal(chunk.embedding()));
        }
    }

    @Transactional
    public void delete(SourceType type, String sourceId) {
        jdbc.update("delete from memory_chunk where source_type = ? and source_id = ?", type.name(), sourceId);
    }

    @Transactional(readOnly = true)
    public Optional<String> contentHash(SourceType type, String sourceId) {
        return jdbc.queryForList(
                "select content_hash from memory_chunk where source_type = ? and source_id = ? limit 1",
                String.class, type.name(), sourceId)
            .stream().findFirst();
    }

    /** The k chunks closest to the query by cosine distance, optionally only of one source type. */
    @Transactional(readOnly = true)
    public List<MemoryHit> search(float[] query, int k, SourceType filter) {
        String vector = literal(query);
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            select source_type, source_id, source_date, title, text, 1 - (embedding <=> ?::vector) as score
            from memory_chunk
            """);
        args.add(vector);
        if (filter != null) {
            sql.append("where source_type = ?\n");
            args.add(filter.name());
        }
        sql.append("order by embedding <=> ?::vector limit ?");
        args.add(vector);
        args.add(k);
        return jdbc.query(sql.toString(), HIT, args.toArray());
    }

    static String literal(float[] vector) {
        if (vector.length != TextEmbedder.DIMENSIONS) {
            throw new IllegalArgumentException(
                "expected " + TextEmbedder.DIMENSIONS + " dimensions, got " + vector.length);
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
