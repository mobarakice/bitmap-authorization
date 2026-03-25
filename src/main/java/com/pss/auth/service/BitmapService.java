package com.pss.auth.service;

import com.pss.auth.config.BitmapConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Connection;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * Low-level bitmap operations service.
 * Handles chunk calculation and individual bit operations on Redis bitmap keys.
 * <p>
 * Key format: {prefix}:{chunkId}
 * Chunk size: configurable (default 1024 bits)
 * <p>
 * For a permission with bit_index=1500 and chunk_size=1024:
 * chunk  = 1500 / 1024 = 1
 * offset = 1500 % 1024 = 476
 * key    = "{prefix}:1"
 * SETBIT key 476 1
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BitmapService {

    private final BitmapConfig bitmapConfig;
    private final RedisTemplate<String, byte[]> redisTemplate;

    /**
     * Get the chunk ID for a given bit index.
     */
    public int getChunkId(int bitIndex) {
        return bitIndex / bitmapConfig.chunkSize();
    }

    /**
     * Get the offset within a chunk for a given bit index.
     */
    public int getOffset(int bitIndex) {
        return bitIndex % bitmapConfig.chunkSize();
    }

    /**
     * Build the full Redis key for a chunk.
     */
    public String buildChunkKey(String prefix, int chunkId) {
        return prefix + ":" + chunkId;
    }

    /**
     * Set a single bit in the appropriate chunk.
     */
    public void setBit(String keyPrefix, int bitIndex) {
        var chunkId = getChunkId(bitIndex);
        var offset = getOffset(bitIndex);
        var key = buildChunkKey(keyPrefix, chunkId);
        redisTemplate.opsForValue().setBit(key, offset, true);
    }

    /**
     * Get a single bit from the appropriate chunk.
     * Returns true (ALLOW) or false (DENY).
     */
    public boolean getBit(String keyPrefix, int bitIndex) {
        var chunkId = getChunkId(bitIndex);
        var offset = getOffset(bitIndex);
        var key = buildChunkKey(keyPrefix, chunkId);
        var result = redisTemplate.opsForValue().getBit(key, offset);
        return result != null && result;
    }

    /**
     * Build a bitmap from a list of bit indexes.
     * Sets all corresponding bits across the necessary chunks.
     */
    public void buildBitmap(String keyPrefix, List<Integer> bitIndexes) {
        if (bitIndexes.isEmpty()) return;

        redisTemplate.executePipelined((RedisCallback<Connection>) connection -> {
            bitIndexes.forEach(bitIndex -> {
                var key = buildChunkKey(keyPrefix, getChunkId(bitIndex));
                connection.stringCommands().setBit(key.getBytes(), getOffset(bitIndex), true);
            });
            return null;
        });
        log.debug("Built bitmap for {} with {} bits (pipelined)", keyPrefix, bitIndexes.size());
    }

    /**
     * Set TTL on all chunk keys for a given prefix.
     */
    public void setTtl(String keyPrefix, int maxBitIndex, long ttlSeconds) {
        var maxChunk = getChunkId(maxBitIndex);
        IntStream.rangeClosed(0, maxChunk)
                .mapToObj(c -> buildChunkKey(keyPrefix, c))
                .forEach(key -> redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS));
    }

    /**
     * Delete all chunk keys for a given prefix.
     */
    public void deleteAll(String keyPrefix, int maxChunkId) {
        IntStream.rangeClosed(0, maxChunkId)
                .mapToObj(c -> buildChunkKey(keyPrefix, c))
                .forEach(redisTemplate::delete);
    }

    /**
     * Get the total number of chunks needed for a given max bit index.
     */
    public int getTotalChunks(int maxBitIndex) {
        return (maxBitIndex / bitmapConfig.chunkSize()) + 1;
    }

    /**
     * Get configured chunk size.
     */
    public int getChunkSize() {
        return bitmapConfig.chunkSize();
    }
}
