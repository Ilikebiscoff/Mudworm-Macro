package com.mudworm.macro;

import com.mudworm.macro.path.Cell;
import com.mudworm.macro.path.Cells;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Predicts Ether Transmission (sneak + right-click with an Aspect of the Void) the conservative way.
 * <p>
 * The server walks the look ray from the sneaking eye and takes the first block that stops it as the target. A wall
 * in the way therefore IS the target, so "never through walls" means: the first block the ray stops at must be the
 * block we want. The mods that predict etherwarp (Odin, Firmament, Skyblocker, NoFrills) disagree on some blocks, so
 * this class only accepts what all of them accept:
 * <ul>
 *   <li>target: a full solid cube that is not interactable, with literal air in the two cells above</li>
 *   <li>ray: only passes air, plus blocks without collision that are not fluids, signs/banners, plates or tripwire
 *       ({@link Config#strictRay}: air only)</li>
 *   <li>every ray in a small cone around the aim ({@link Config#coneDeg}) must stop at a valid target</li>
 * </ul>
 */
public final class Etherwarp {
    private Etherwarp() {}

    /** Eye height while sneaking on modern islands (Galatea). 1.62 standing, 1.54 on legacy 1.8 islands. */
    public static final double SNEAK_EYE = 1.27;

    /** A teleport we can do: the target block and the look angles that hit it. */
    public record Shot(BlockPos target, float yaw, float pitch) {
        /** Where the feet end up. */
        public BlockPos landing() {
            return target.above();
        }
    }

    // ---- item -----------------------------------------------------------------------------------------------

    /** True when the stack is an AOTE / AOTV with Ether Transmission applied. */
    public static boolean hasEther(ItemStack st) {
        CustomData cd = st.get(DataComponents.CUSTOM_DATA);
        return cd != null && cd.copyTag().getIntOr("ethermerge", 0) == 1;
    }

    /** 57 (or {@link Config#baseRange}) + Transmission Tuners on the item, minus the safety margin. */
    public static double range(ItemStack st) {
        int tuners = 0;
        CustomData cd = st.get(DataComponents.CUSTOM_DATA);
        if (cd != null) tuners = Math.max(0, Math.min(4, cd.copyTag().getIntOr("tuned_transmission", 0)));
        return Config.baseRange + tuners - Config.rangeMargin;
    }

    // ---- block rules ----------------------------------------------------------------------------------------

    /** Right-clicking these uses the block instead of the item, so they are never targets. */
    public static boolean interactable(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
                || b instanceof AnvilBlock || b instanceof AbstractCauldronBlock || b instanceof NoteBlock
                || b instanceof DiodeBlock;
    }

    /** A block every model agrees you can etherwarp onto: full non-interactable cube, literal air in the two cells above. */
    public static boolean validTarget(Cells c, int x, int y, int z) {
        return (c.at(x, y, z) & Cell.TARGET) != 0 && (c.at(x, y + 1, z) & Cell.AIR) != 0
                && (c.at(x, y + 2, z) & Cell.AIR) != 0;
    }

    // ---- ray ------------------------------------------------------------------------------------------------

    /**
     * Exact voxel walk (Amanatides-Woo DDA) from {@code from} along unit {@code dir}: the first cell the ray stops
     * at within {@code maxDist}, or null (nothing hit, or an unknown cell on the way). {@code airOnly}: only air lets
     * the ray through (used for "can I see the spot").
     */
    public static BlockPos firstStop(Cells c, Vec3 from, Vec3 dir, double maxDist, boolean airOnly) {
        int x = (int) Math.floor(from.x), y = (int) Math.floor(from.y), z = (int) Math.floor(from.z);
        int sx = dir.x > 0 ? 1 : dir.x < 0 ? -1 : 0;
        int sy = dir.y > 0 ? 1 : dir.y < 0 ? -1 : 0;
        int sz = dir.z > 0 ? 1 : dir.z < 0 ? -1 : 0;
        double dx = sx != 0 ? Math.abs(1.0 / dir.x) : Double.POSITIVE_INFINITY;
        double dy = sy != 0 ? Math.abs(1.0 / dir.y) : Double.POSITIVE_INFINITY;
        double dz = sz != 0 ? Math.abs(1.0 / dir.z) : Double.POSITIVE_INFINITY;
        double tx = sx > 0 ? (x + 1 - from.x) * dx : sx < 0 ? (from.x - x) * dx : Double.POSITIVE_INFINITY;
        double ty = sy > 0 ? (y + 1 - from.y) * dy : sy < 0 ? (from.y - y) * dy : Double.POSITIVE_INFINITY;
        double tz = sz > 0 ? (z + 1 - from.z) * dz : sz < 0 ? (from.z - z) * dz : Double.POSITIVE_INFINITY;
        boolean strict = airOnly || Config.strictRay;
        while (true) {
            double t;
            if (tx < ty && tx < tz) {
                x += sx;
                t = tx;
                tx += dx;
            } else if (ty < tz) {
                y += sy;
                t = ty;
                ty += dy;
            } else {
                z += sz;
                t = tz;
                tz += dz;
            }
            if (t > maxDist) return null;
            short f = c.at(x, y, z);
            if ((f & Cell.LOADED) == 0) return null;
            boolean passes = (f & Cell.AIR) != 0 || (!strict && (f & Cell.RAYPASS) != 0);
            if (!passes) return new BlockPos(x, y, z);
        }
    }

    /** Where an etherwarp with these exact angles from {@code eye} lands its target, or null if it would fail. */
    public static BlockPos predict(Cells c, Vec3 eye, float yaw, float pitch, double range) {
        BlockPos hit = firstStop(c, eye, dir(yaw, pitch), range, false);
        return hit != null && validTarget(c, hit.getX(), hit.getY(), hit.getZ()) ? hit : null;
    }

    /** Rays in the cone may land at most this far (blocks) from the planned target. */
    private static final double MAX_SPREAD = 12.0;

    /**
     * True when the centre ray stops at {@code target} and every ray in the cone around it stops at a valid
     * etherwarp target (so a tiny aim error can never turn into a wall hit) not too far from it.
     */
    public static boolean robust(Cells c, Vec3 eye, float yaw, float pitch, BlockPos target, double range) {
        if (!target.equals(firstStop(c, eye, dir(yaw, pitch), range, false))) return false;
        float j = (float) Config.coneDeg;
        float[][] offs = {{j, 0}, {-j, 0}, {0, j}, {0, -j}};
        for (float[] o : offs) {
            BlockPos hit = firstStop(c, eye, dir(yaw + o[0], pitch + o[1]), range, false);
            if (hit == null || !validTarget(c, hit.getX(), hit.getY(), hit.getZ())) return false;
            if (hit.distSqr(target) > MAX_SPREAD * MAX_SPREAD) return false;
        }
        return true;
    }

    /**
     * Finds look angles from {@code eye} that robustly etherwarp onto {@code target}, or null. Tries the top face
     * centre first (lands cleanly) and then the block centre (for blocks seen from the side).
     */
    public static Shot shot(Cells c, Vec3 eye, BlockPos target, double range) {
        if (eye.distanceTo(target.getCenter()) > range || !validTarget(c, target.getX(), target.getY(), target.getZ())) {
            return null;
        }
        Vec3[] aims = {
                new Vec3(target.getX() + 0.5, target.getY() + 0.97, target.getZ() + 0.5),
                target.getCenter()
        };
        for (Vec3 a : aims) {
            float[] ang = angles(eye, a);
            if (robust(c, eye, ang[0], ang[1], target, range)) return new Shot(target, ang[0], ang[1]);
        }
        return null;
    }

    /** Why {@link #shot} gave up on {@code target}: 1 out of range, 2 not a valid target, 3 the aim cone is not safe. */
    public static int reject(Cells c, Vec3 eye, BlockPos target, double range) {
        if (eye.distanceTo(target.getCenter()) > range) return 1;
        if (!validTarget(c, target.getX(), target.getY(), target.getZ())) return 2;
        return 3;
    }

    // ---- maths ----------------------------------------------------------------------------------------------

    /** Vanilla's look vector for yaw/pitch (degrees). */
    public static Vec3 dir(float yaw, float pitch) {
        double yr = Math.toRadians(-yaw) - Math.PI, pr = Math.toRadians(-pitch);
        double cp = -Math.cos(pr);
        return new Vec3(Math.sin(yr) * cp, Math.sin(pr), Math.cos(yr) * cp);
    }

    /** Yaw and pitch (degrees) to look from {@code from} at {@code to}. */
    public static float[] angles(Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new float[]{yaw, pitch};
    }

    /** Sneaking eye when standing in the middle of {@code feet}. */
    public static Vec3 eyeAt(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + SNEAK_EYE, feet.getZ() + 0.5);
    }
}
