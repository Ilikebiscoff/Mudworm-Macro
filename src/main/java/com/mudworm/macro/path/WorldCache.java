package com.mudworm.macro.path;

import com.mudworm.macro.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshot of the terrain around the player, one {@link Cell} per block. The game thread fills it a few
 * milliseconds per tick (nearest chunks first, one 16x16 layer at a time, finished columns are swapped in whole);
 * the planner thread only reads it. That keeps the pathfinder off the game thread, so planning cannot cause lag.
 */
public final class WorldCache {
    private static final int LAYER = 256;

    static final class Column {
        final int minY, h;
        final short[] cells;
        final long builtAt;
        volatile long[] targets;

        Column(int minY, int h, short[] cells, long builtAt) {
            this.minY = minY;
            this.h = h;
            this.cells = cells;
            this.builtAt = builtAt;
        }
    }

    private final ConcurrentHashMap<Long, Column> cols = new ConcurrentHashMap<>();
    private final ArrayDeque<Long> todo = new ArrayDeque<>();
    private ClientLevel level;
    private long lastSchedule;

    // column being filled
    private short[] wip;
    private long wipKey;
    private int wipMinY, wipH, wipLayer;

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    public void clear() {
        cols.clear();
        todo.clear();
        wip = null;
    }

    /** True once the column under {@code p} is in the snapshot. */
    public boolean has(BlockPos p) {
        return cols.containsKey(key(p.getX() >> 4, p.getZ() >> 4));
    }

    public int columns() {
        return cols.size();
    }

    /** A reader for one thread. */
    public Reader reader() {
        return new Reader();
    }

    // ---- filling (game thread) ------------------------------------------------------------------------------

    public void tick(Minecraft mc, long budgetNs) {
        ClientLevel lv = mc.level;
        var p = mc.player;
        if (lv == null || p == null) return;
        if (lv != level) {
            clear();
            level = lv;
        }
        long now = System.currentTimeMillis();
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        if (now - lastSchedule > 300) {
            lastSchedule = now;
            schedule(lv, pcx, pcz, now);
        }
        long deadline = System.nanoTime() + budgetNs;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (System.nanoTime() < deadline) {
            if (wip == null && !begin(lv)) break;
            fillLayer(lv, m, now);
        }
    }

    private void schedule(ClientLevel lv, int pcx, int pcz, long now) {
        int r = Math.max(2, Config.cacheChunks);
        List<long[]> want = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > r * r) continue;
                int cx = pcx + dx, cz = pcz + dz;
                if (!lv.hasChunk(cx, cz)) continue;
                long k = key(cx, cz);
                Column c = cols.get(k);
                long prio;
                if (c == null) {
                    prio = d2 - 100_000L;
                } else {
                    long stale = d2 <= 9 ? 8_000 : 45_000; // near the player trees / blocks change more often
                    if (now - c.builtAt < stale) continue;
                    prio = d2;
                }
                want.add(new long[]{prio, k});
            }
        }
        want.sort((a, b) -> Long.compare(a[0], b[0]));
        todo.clear();
        for (long[] w : want) todo.add(w[1]);
        int far = r + 2;
        cols.keySet().removeIf(k -> Math.abs((int) (k >> 32) - pcx) > far || Math.abs((int) (long) k - pcz) > far);
    }

    private boolean begin(ClientLevel lv) {
        Long k = todo.poll();
        if (k == null) return false;
        int cx = (int) (k >> 32), cz = (int) (long) k;
        if (!lv.hasChunk(cx, cz)) return true; // gone again; try the next one
        wipKey = k;
        wipMinY = Config.scanMinY;
        wipH = Math.max(16, Config.scanMaxY - Config.scanMinY);
        wip = new short[wipH * LAYER];
        wipLayer = 0;
        return true;
    }

    private void fillLayer(ClientLevel lv, BlockPos.MutableBlockPos m, long now) {
        int cx = (int) (wipKey >> 32), cz = (int) wipKey;
        int y = wipMinY + wipLayer;
        int base = wipLayer * LAYER;
        int x0 = cx << 4, z0 = cz << 4;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                m.set(x0 + lx, y, z0 + lz);
                if (lv.isOutsideBuildHeight(m)) continue;
                BlockState s = lv.getBlockState(m);
                wip[base + (lz << 4) + lx] = Cell.classify(lv, m, s);
            }
        }
        if (++wipLayer >= wipH) {
            cols.put(wipKey, new Column(wipMinY, wipH, wip, now));
            wip = null;
        }
    }

    // ---- reading (any thread, one Reader per thread) --------------------------------------------------------

    public final class Reader implements Cells {
        private Column last;
        private int lcx = Integer.MIN_VALUE, lcz;

        @Override
        public short at(int x, int y, int z) {
            int cx = x >> 4, cz = z >> 4;
            if (cx != lcx || cz != lcz) {
                last = cols.get(key(cx, cz));
                lcx = last == null ? Integer.MIN_VALUE : cx;
                lcz = cz;
            }
            Column c = last;
            if (c == null) return 0;
            int ly = y - c.minY;
            if (ly < 0) return 0;
            if (ly >= c.h) return Cell.SKY;
            return c.cells[(ly << 8) | ((z & 15) << 4) | (x & 15)];
        }

        /** Etherwarp landing candidates in a chunk (packed {@link BlockPos} longs), or null if it is not cached. */
        public long[] targets(int cx, int cz) {
            Column c = cols.get(key(cx, cz));
            if (c == null) return null;
            long[] t = c.targets;
            if (t != null) return t;
            long[] out = new long[256];
            int n = 0;
            for (int ly = 0; ly < c.h; ly++) {
                for (int i = 0; i < LAYER; i++) {
                    int idx = ly * LAYER + i;
                    if ((c.cells[idx] & Cell.TARGET) == 0) continue;
                    boolean a1 = ly + 1 >= c.h || (c.cells[idx + LAYER] & Cell.AIR) != 0;
                    boolean a2 = ly + 2 >= c.h || (c.cells[idx + 2 * LAYER] & Cell.AIR) != 0;
                    if (!a1 || !a2) continue;
                    if (n == out.length) out = java.util.Arrays.copyOf(out, n * 2);
                    out[n++] = BlockPos.asLong((cx << 4) + (i & 15), c.minY + ly, (cz << 4) + (i >> 4));
                }
            }
            t = java.util.Arrays.copyOf(out, n);
            c.targets = t;
            return t;
        }
    }
}
