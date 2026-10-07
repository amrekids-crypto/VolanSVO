package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.human.AimModel;
import dev.volansvo.svo.bots.nms.BotNms;
import org.bukkit.entity.Player;

/**
 * «Руки и ноги» бота: плавный поворот головы с ограниченной скоростью (как мышь у
 * человека) и перевод желаемого направления движения в нажатия W/A/S/D относительно
 * того, куда бот реально смотрит. Благодаря этому бот может бежать в одну сторону и
 * целиться в другую (стрейф в бою).
 */
public final class Motor {

    private float yaw, pitch;
    private boolean init;
    private AimModel aim;

    /** Включить модель руки для ведения цели в бою. */
    public void setAim(AimModel aim) { this.aim = aim; }

    /** Новая цель или она появилась снова: точность набирается заново. */
    public void acquire() { if (aim != null) aim.acquire(); }

    /** Во сколько раз разброс сейчас больше обычного: первые выстрелы по новой цели хуже. */
    public double warm() { return aim == null ? 1.0 : 1.0 + 1.2 * Math.exp(-aim.onTarget() / 14.0); }

    /** Вести цель: поправки порциями, как рука с мышью. Без модели - обычный поворот. */
    public void track(Player p, float targetYaw, float targetPitch, float maxTurn) {
        if (aim == null) { turn(p, targetYaw, targetPitch, maxTurn); return; }
        if (!init) sync(p);
        aim.yaw = yaw; aim.pitch = pitch; aim.maxSpeed = maxTurn;
        aim.step(targetYaw, targetPitch);
        yaw = aim.yaw; pitch = aim.pitch;
        BotNms.look(p, yaw, pitch);
    }

    /** Дёрнуть прицел (вздрогнул от попадания). */
    public void nudge(Player p, float dYaw, float dPitch) {
        if (!init) sync(p);
        yaw = wrap(yaw + dYaw);
        pitch = Math.max(-90f, Math.min(90f, pitch + dPitch));
        BotNms.look(p, yaw, pitch);
    }

    public float yaw() { return yaw; }
    public float pitch() { return pitch; }

    public void sync(Player p) {
        yaw = p.getLocation().getYaw();
        pitch = p.getLocation().getPitch();
        init = true;
    }

    /** Поворачивает взгляд к (targetYaw, targetPitch) не быстрее maxTurn градусов за тик. */
    public void turn(Player p, float targetYaw, float targetPitch, float maxTurn) {
        if (!init) sync(p);
        float dy = wrap(targetYaw - yaw);
        float dp = targetPitch - pitch;
        // Быстрее на больших углах, плавно дотягивает на малых (как рука с мышью).
        float stepY = Math.min(Math.abs(dy), Math.max(maxTurn * 0.35f, Math.abs(dy) * 0.55f));
        stepY = Math.min(stepY, maxTurn);
        float stepP = Math.min(Math.abs(dp), Math.max(maxTurn * 0.25f, Math.abs(dp) * 0.5f));
        stepP = Math.min(stepP, maxTurn);
        yaw = wrap(yaw + Math.signum(dy) * stepY);
        pitch = Math.max(-90f, Math.min(90f, pitch + Math.signum(dp) * stepP));
        BotNms.look(p, yaw, pitch);
    }

    /**
     * Двигаться в мировом направлении (dx, dz) со скоростью speed (0..1),
     * плюс боковое смещение strafe (-1..1, плюс = влево).
     */
    public void drive(Player p, double dx, double dz, double speed, float strafe, boolean jump, boolean wantSprint) {
        double len = Math.sqrt(dx * dx + dz * dz);
        float fwd = 0f, side = 0f;
        if (len > 1e-4 && speed > 0) {
            double nx = dx / len, nz = dz / len;
            double yr = Math.toRadians(yaw);
            double sin = Math.sin(yr), cos = Math.cos(yr);
            fwd = (float) ((-nx * sin + nz * cos) * speed);
            side = (float) ((nx * cos + nz * sin) * speed);
        }
        side += strafe;
        // Нормируем, чтобы по диагонали не бежать быстрее.
        float m = (float) Math.sqrt(fwd * fwd + side * side);
        if (m > 1f) { fwd /= m; side /= m; }
        BotNms.input(p, fwd, side, jump);
        boolean sprint = wantSprint && fwd > 0.8f && p.getFoodLevel() > 6 && !p.isSneaking();
        BotNms.sprint(p, sprint);
    }

    public void stop(Player p) {
        BotNms.input(p, 0f, 0f, false);
        BotNms.sprint(p, false);
    }

    public static float yawTo(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static float pitchTo(double dx, double dy, double dz) {
        double flat = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, flat));
    }

    public static float wrap(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }
}
