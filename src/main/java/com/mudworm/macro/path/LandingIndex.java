package com.mudworm.macro.path;

import com.mudworm.macro.Config;
import com.mudworm.macro.Etherwarp;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Every block in the loaded world that is a valid etherwarp target ({@link Etherwarp#validTarget}), per chunk.
 * Built lazily when the planner asks for a chunk, kept for 30 s (trees get chopped and regrow on Galatea).
 */
public final class LandingIndex {
    private static final long TTL_MS = 30_000;

    private record Entry(long builtAt, long[] targets) {}

    private final Map<Long, Entry> chunks = new HashMap<>();
    private Level level;

    /** Targets in chunk (cx, cz) as packed BlockPos longs, or null if the chunk is not loaded. */
    public long[] chunk(Level lv, int cx, int cz) {
        if (lv != level) {
            chunks.clear();
            level = lv;
        }
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        long now = System.currentTimeMillis();
        Entry e = chunks.get(key);
        if (e != null && now - e.builtAt() < TTL_MS) return e.targets();
        if (!lv.hasChunk(cx, cz)) return null;
        long[] t = build(lv, cx, cz);
        chunks.put(key, new Entry(now, t));
        return t;
    }

    public void clear() {
        chunks.clear();
    }

    private static long[] build(Level lv, int cx, int cz) {
        long[] out = new long[256];
        int n = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int minY = Config.scanMinY, maxY = Config.scanMaxY;
        for (int x = cx << 4; x < (cx << 4) + 16; x++) {
            for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                // walk down the column: a target is a full block with two air blocks above it
                int air = 0;
                for (int y = maxY + 2; y >= minY; y--) {
                    m.set(x, y, z);
                    var st = lv.getBlockState(m);
                    if (st.isAir()) {
                        air++;
                        continue;
                    }
                    if (air >= 2 && Etherwarp.validTarget(lv, m)) {
                        if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                        out[n++] = m.asLong();
                    }
                    air = 0;
                }
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
