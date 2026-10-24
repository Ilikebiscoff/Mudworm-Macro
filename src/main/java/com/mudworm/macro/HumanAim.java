package com.mudworm.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Camera control that behaves like a hand on a mouse. It runs once per rendered frame (not once per
 * 20 Hz tick, which looks choppy) and feeds the player the same pixel-sized turns a real mouse does.
 * <ul>
 *   <li>damped-spring tracking: smooth bell-shaped speed, fast for far targets, gentle for near ones</li>
 *   <li>per-target "feel": different stiffness/damping each time, so some flicks overshoot slightly</li>
 *   <li>reaction delay when a new target appears</li>
 *   <li>a held aiming error per target, slow hand drift and a faint tremor</li>
 *   <li>output quantised to whole mouse pixels at your sensitivity, remainder carried over</li>
 * </ul>
 */
public class HumanAim {
    private final Random rand = new Random();
    private final float[] ph = new float[6];

    private float tYaw, tPitch;           // where we want to look
    private float offYaw, offPitch;       // aiming error held for the current target
    private float velYaw, velPitch;       // deg/s
    private float pxYaw, pxPitch;         // carried sub-pixel remainder
    private float omega = 11f, zeta = 0.9f;
    private float maxSpeed = 1000f, stiffness = 1f;
    private double time, holdUntil;
    private long lastNs;
    private boolean exact;

    public HumanAim() {
        for (int i = 0; i < ph.length; i++) ph[i] = rand.nextFloat() * 6.2832f;
        retarget();
    }

    /** Stops all motion (macro start/stop). */
    public void reset() {
        velYaw = velPitch = 0f;
        pxYaw = pxPitch = 0f;
        lastNs = 0;
    }

    /** Call when the thing being looked at changed (new mushroom): new reaction time, error and feel. */
    public void retarget() {
        offYaw = (float) rand.nextGaussian() * 0.35f;
        offPitch = (float) rand.nextGaussian() * 0.25f;
        omega = 9f + rand.nextFloat() * 6f;
        zeta = 0.78f + rand.nextFloat() * 0.22f;
        holdUntil = time + 0.09 + rand.nextFloat() * 0.13;
    }

    /** Like {@link #retarget()} but no reaction delay, tiny aiming error and a stiff spring: for quick hits. */
    public void retargetFast() {
        offYaw = (float) rand.nextGaussian() * 0.12f;
        offPitch = (float) rand.nextGaussian() * 0.10f;
        omega = 24f + rand.nextFloat() * 6f;
        zeta = 0.9f;
        holdUntil = time + 0.02;
    }

    /** Exact aim for etherwarps: no held aiming error, no drift, stiff spring. */
    public void precise() {
        offYaw = offPitch = 0f;
        omega = 22f;
        zeta = 1f;
        holdUntil = time + 0.03;
        exact = true;
    }

    /** Back to normal hand-like aim (after {@link #precise()}). */
    public void loose() {
        exact = false;
    }

    /** Sets the absolute angles to track (call every tick, cheap). */
    public void setTarget(float yaw, float pitch) {
        setTarget(yaw, pitch, 1000f, 1f);
    }

    /**
     * @param maxSpeed  turn speed cap in deg/s (walking uses a low one so it never whips around)
     * @param stiffness multiplier on the spring (below 1 = lazier, smoother tracking)
     */
    public void setTarget(float yaw, float pitch, float maxSpeed, float stiffness) {
        tYaw = yaw;
        tPitch = pitch;
        this.maxSpeed = maxSpeed;
        this.stiffness = stiffness;
    }

    /** True when the crosshair rests within {@code tol} degrees of the target. */
    public boolean settled(LocalPlayer p, float tol) {
        float ey = Mth.wrapDegrees(tYaw - p.getYRot());
        float ep = tPitch - p.getXRot();
        return time >= holdUntil && Math.hypot(ey, ep) <= tol && Math.hypot(velYaw, velPitch) < 40f;
    }

    /** Advances the camera by the real time since the last call. Call once per rendered frame. */
    public void frame(Minecraft mc, LocalPlayer p) {
        long now = System.nanoTime();
        if (lastNs == 0) lastNs = now;
        float dt = Mth.clamp((now - lastNs) / 1e9f, 0.001f, 0.033f);
        lastNs = now;
        time += dt;

        float drift = exact ? 0f : 1f;
        float wantYaw = tYaw + offYaw + drift
                * (0.35f * (float) Math.sin(time * 0.9 + ph[0]) + 0.15f * (float) Math.sin(time * 2.3 + ph[1]));
        float wantPitch = tPitch + offPitch + drift
                * (0.22f * (float) Math.sin(time * 0.7 + ph[2]) + 0.10f * (float) Math.sin(time * 1.9 + ph[3]));

        float errYaw = Mth.wrapDegrees(wantYaw - p.getYRot());
        float errPitch = wantPitch - p.getXRot();

        if (time < holdUntil) {
            // reacting: the hand hasn't started moving yet
            float decay = (float) Math.exp(-12f * dt);
            velYaw *= decay;
            velPitch *= decay;
        } else {
            float w = omega * stiffness;
            // walking (turn speed capped): critically/over-damped so it never overshoots and sways
            float zeta = maxSpeed < 500f ? Math.max(this.zeta, 1.15f) : this.zeta;
            float wy = w, wp = w * 0.85f; // vertical is a bit lazier than horizontal
            velYaw += (wy * wy * errYaw - 2f * zeta * wy * velYaw) * dt;
            velPitch += (wp * wp * errPitch - 2f * zeta * wp * velPitch) * dt;
            float sp = (float) Math.hypot(velYaw, velPitch);
            float max = maxSpeed;
            if (sp > max) {
                velYaw *= max / sp;
                velPitch *= max / sp;
            }
        }

        float dYaw = velYaw * dt + drift * 0.015f * (float) Math.sin(time * 47 + ph[4]);
        float dPitch = velPitch * dt + drift * 0.010f * (float) Math.sin(time * 53 + ph[5]);

        // feed whole mouse pixels, like real input; carry the remainder so nothing is lost
        double sens = mc.options.sensitivity().get();
        double f = sens * 0.6 + 0.2;
        float e = (float) (f * f * f * 8.0);   // what MouseHandler multiplies pixels by
        float step = e * 0.15f;                // degrees per mouse pixel
        float ty = dYaw / step + pxYaw, tp = dPitch / step + pxPitch;
        int wy = Math.round(ty), wp = Math.round(tp);
        pxYaw = ty - wy;
        pxPitch = tp - wp;
        if (wy != 0 || wp != 0) p.turn(wy * e, wp * e);
    }
}
