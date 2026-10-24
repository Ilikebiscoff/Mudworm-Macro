package com.mudworm.macro.path;

import com.mudworm.macro.Etherwarp;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block boiled down to the few facts the pathfinder and the etherwarp rules need, packed in a short. The same
 * function fills the terrain snapshot and the live check right before an etherwarp, so both agree.
 */
public final class Cell {
    private Cell() {}

    /** Set on every known block (0 = unknown / not loaded). */
    public static final int LOADED = 1;
    /** Literally air. */
    public static final int AIR = 2;
    /** The etherwarp ray goes through (air, or no collision and not a fluid / sign / plate / tripwire). */
    public static final int RAYPASS = 4;
    /** A full solid cube that is not interactable: a valid etherwarp target (headroom is checked separately). */
    public static final int TARGET = 8;
    /** Has collision. */
    public static final int COLL = 16;
    /** Collision top at most 0.6 (slab, carpet, snow layer). */
    public static final int LOW = 32;
    /** Collision above 1 block (fence, wall): cannot pass, cannot stand on. */
    public static final int TALL = 64;
    public static final int FLUID = 128;
    /** Lava, fire, cactus, magma, berries, campfire, powder snow, cobweb. */
    public static final int HAZARD = 256;
    /** Soul sand, honey, mud. */
    public static final int SLOW = 512;

    /** Azalea leaves: solid, but the macro breaks them when they are in the way. */
    public static final int BREAKABLE = 1024;

    /** Open sky above the scanned height range. */
    public static final short SKY = (short) (LOADED | AIR | RAYPASS);

    public static short classify(Level level, BlockPos pos, BlockState s) {
        if (s.isAir()) return SKY;
        int f = LOADED;
        var fluid = s.getFluidState();
        boolean wet = !fluid.isEmpty();
        if (wet) {
            f |= FLUID;
            if (fluid.is(FluidTags.LAVA)) f |= HAZARD;
        }
        var shape = s.getCollisionShape(level, pos);
        boolean coll = !shape.isEmpty();
        if (coll) {
            f |= COLL;
            double top = shape.max(Direction.Axis.Y);
            if (top <= 0.6) f |= LOW;
            else if (top > 1.0) f |= TALL;
        }
        Block b = s.getBlock();
        if (b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.WITHER_ROSE || b == Blocks.CAMPFIRE
                || b == Blocks.SOUL_CAMPFIRE || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB) f |= HAZARD;
        if (b == Blocks.AZALEA_LEAVES || b == Blocks.FLOWERING_AZALEA_LEAVES) f |= BREAKABLE;
        if (b == Blocks.SOUL_SAND || b == Blocks.HONEY_BLOCK || b == Blocks.MUD) f |= SLOW;
        if (!coll && !wet && !s.hasBlockEntity()
                && !(b instanceof BasePressurePlateBlock || b instanceof TripWireBlock || b instanceof ButtonBlock)) {
            f |= RAYPASS;
        }
        if (coll && !wet && (f & HAZARD) == 0 && !Etherwarp.interactable(s) && s.isCollisionShapeFullBlock(level, pos)) f |= TARGET;
        return (short) f;
    }

    /** Reads the live world (main thread only). Used for the final check right before an etherwarp. */
    public static final class Live implements Cells {
        private final Level level;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        public Live(Level level) {
            this.level = level;
        }

        @Override
        public short at(int x, int y, int z) {
            m.set(x, y, z);
            if (level.isOutsideBuildHeight(m) || !level.isLoaded(m)) return 0;
            return classify(level, m, level.getBlockState(m));
        }
    }
}
