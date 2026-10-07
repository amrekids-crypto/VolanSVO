package dev.volansvo.svo.bots.human;

import java.util.Random;

/**
 * Рука с мышью. Человек не подтягивает прицел к цели каждый миг: он замечает ошибку и
 * раз в 100-200 мс задаёт руке новое движение, а между поправками рука просто едет.
 * Размах движения даёт ошибку конечной точки, и она уменьшается, пока цель держат в прицеле.
 *
 * Перелёт, доводка и отставание за резко свернувшей целью получаются отсюда сами.
 */
public final class AimModel {

    public float yaw, pitch;

    /** Тиков между поправками. */
    public int period = 3;
    /** Предел скорости поворота, градусов за тик. */
    public float maxSpeed = 34f;
    /** Ошибка конечной точки в долях размаха движения. */
    public double endpointNoise = 0.07;

    private final Random rnd;
    private float velYaw, velPitch;
    private int untilCorrect;
    private int onTarget;
    /** Скорость самой цели по экрану, как её оценивает глаз: рука едет вместе с целью. */
    private float followYaw, followPitch, lastYaw, lastPitch;
    private boolean seen;

    public AimModel(Random rnd) {
        this.rnd = rnd;
    }

    /** Новая цель или она появилась снова: точность набирается заново. */
    public void acquire() {
        onTarget = 0;
        untilCorrect = 0;
        seen = false;
        followYaw = 0f;
        followPitch = 0f;
    }

    /** Сколько тиков подряд ведём цель. */
    public int onTarget() { return onTarget; }

    /** Во сколько раз ошибка сейчас больше установившейся (в начале - в 1.0/0.35 раза). */
    public double settle() {
        return 0.35 + 0.65 * Math.exp(-onTarget / 14.0);
    }

    /** Один тик движения к точке (targetYaw, targetPitch). */
    public void step(float targetYaw, float targetPitch) {
        if (seen) {
            followYaw = followYaw * 0.5f + clamp(wrap(targetYaw - lastYaw), 6f) * 0.5f;
            followPitch = followPitch * 0.5f + clamp(targetPitch - lastPitch, 6f) * 0.5f;
        }
        lastYaw = targetYaw; lastPitch = targetPitch; seen = true;
        if (--untilCorrect <= 0) {
            untilCorrect = period + (rnd.nextInt(3) == 0 ? 1 : 0);
            float dy = wrap(targetYaw - yaw);
            float dp = targetPitch - pitch;
            float amp = (float) Math.sqrt(dy * dy + dp * dp);
            if (amp < 0.4f) {
                velYaw = followYaw;
                velPitch = followPitch;
            } else {
                double noise = amp * endpointNoise * settle();
                dy += (float) (rnd.nextGaussian() * noise);
                dp += (float) (rnd.nextGaussian() * noise * 0.6);
                // За время до следующей поправки проходим почти всю ошибку.
                float n = untilCorrect;
                velYaw = clamp(dy * 0.9f / n + followYaw, maxSpeed);
                velPitch = clamp(dp * 0.9f / n + followPitch, maxSpeed);
            }
        }
        yaw = wrap(yaw + velYaw);
        pitch = Math.max(-90f, Math.min(90f, pitch + velPitch));
        onTarget++;
    }

    private static float clamp(float v, float max) {
        return Math.max(-max, Math.min(max, v));
    }

    public static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }
}
