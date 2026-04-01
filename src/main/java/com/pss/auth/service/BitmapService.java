package com.pss.auth.service;

import com.pss.auth.config.BitmapConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Connection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * Batch get multiple bits via pipeline. Returns list of booleans in same order as input.
     */
    public List<Boolean> getBitsBatch(String keyPrefix, List<Integer> bitIndexes) {
        if (bitIndexes.isEmpty()) return List.of();

        var results = redisTemplate.executePipelined((RedisCallback<Connection>) connection -> {
            bitIndexes.forEach(bitIndex -> {
                var key = buildChunkKey(keyPrefix, getChunkId(bitIndex));
                connection.stringCommands().getBit(key.getBytes(), getOffset(bitIndex));
            });
            return null;
        });

        return results.stream()
                .map(r -> Boolean.TRUE.equals(r))
                .toList();
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
     * Uses pipelining to send all EXPIRE commands in a single round-trip.
     */
    public void setTtl(String keyPrefix, int maxBitIndex, long ttlSeconds) {
        var maxChunk = getChunkId(maxBitIndex);
        var keys = IntStream.rangeClosed(0, maxChunk)
                .mapToObj(c -> buildChunkKey(keyPrefix, c))
                .toList();
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            keys.forEach(key -> connection.keyCommands()
                    .expire(key.getBytes(), ttlSeconds));
            return null;
        });
        log.debug("Set TTL on {} chunks for {} (pipelined)", keys.size(), keyPrefix);
    }

    /**
     * Delete all chunk keys for a given prefix.
     * Uses SCAN to find all matching chunks regardless of maxBitIndex to prevent orphans.
     */
    public void deleteAll(String keyPrefix, int maxChunkId) {
        var pattern = keyPrefix + ":*";
        var keysToDelete = new java.util.ArrayList<String>();
        try (var cursor = redisTemplate.getConnectionFactory().getConnection().keyCommands()
                .scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(100).build())) {
            while (cursor.hasNext()) {
                keysToDelete.add(new String(cursor.next()));
            }
        }
        if (!keysToDelete.isEmpty()) {
            redisTemplate.delete(keysToDelete);
            log.debug("Deleted {} chunks for {} via SCAN", keysToDelete.size(), keyPrefix);
        }
    }

    /**
     * Clear a single bit in the appropriate chunk (SETBIT to 0).
     */
    public void clearBit(String keyPrefix, int bitIndex) {
        var chunkId = getChunkId(bitIndex);
        var offset = getOffset(bitIndex);
        var key = buildChunkKey(keyPrefix, chunkId);
        redisTemplate.opsForValue().setBit(key, offset, false);
    }

    /**
     * Clear multiple bits via pipeline (SETBIT 0 for each).
     */
    public void clearBits(String keyPrefix, List<Integer> bitIndexes) {
        if (bitIndexes.isEmpty()) return;

        redisTemplate.executePipelined((RedisCallback<Connection>) connection -> {
            bitIndexes.forEach(bitIndex -> {
                var key = buildChunkKey(keyPrefix, getChunkId(bitIndex));
                connection.stringCommands().setBit(key.getBytes(), getOffset(bitIndex), false);
            });
            return null;
        });
        log.debug("Cleared {} bits for {} (pipelined)", bitIndexes.size(), keyPrefix);
    }

    /**
     * Get remaining TTL (in seconds) for a chunk key. Returns -1 if no TTL, -2 if key missing.
     */
    public long getRemainingTtl(String keyPrefix, int chunkId) {
        var key = buildChunkKey(keyPrefix, chunkId);
        var ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
        return ttl != null ? ttl : -2;
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

    /**
     * Copy all chunks from a source prefix to a target prefix.
     * Useful for moving bitmaps across Redis Cluster hash tag boundaries.
     */
    public void copyBitmap(String sourcePrefix, String targetPrefix, int maxChunkId, int ttlSeconds) {
        var sourceKeys = IntStream.rangeClosed(0, maxChunkId)
                .mapToObj(c -> buildChunkKey(sourcePrefix, c))
                .toList();
        var bytesList = redisTemplate.opsForValue().multiGet(sourceKeys);
        if (bytesList != null) {
            Map<String, byte[]> targetMap = new HashMap<>();
            for (int i = 0; i <= maxChunkId; i++) {
                var bytes = bytesList.get(i);
                if (bytes != null) {
                    targetMap.put(buildChunkKey(targetPrefix, i), bytes);
                }
            }
            if (!targetMap.isEmpty()) {
                redisTemplate.opsForValue().multiSet(targetMap);
                setTtl(targetPrefix, maxChunkId * bitmapConfig.chunkSize(), ttlSeconds);
            }
        }
    }
}
