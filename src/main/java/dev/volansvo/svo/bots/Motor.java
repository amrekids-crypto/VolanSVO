package dev.volansvo.svo.bots;

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
        // Забор, стена, калитка выше прыжка: прыгать в них бесполезно (так бот и скакал у забора).
        if (jump && len > 1e-4 && jumpUseless(p, dx / len, dz / len)) jump = false;
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

    /**
     * Впереди препятствие, которое прыжком не взять: у блока на уровне ног хитбокс выше
     * 1.2 (забор, стена, калитка - полтора блока) или над ним ещё один твёрдый блок.
     */
    public static boolean jumpUseless(Player p, double nx, double nz) {
        org.bukkit.Location l = p.getLocation();
        return tallAt(l.getWorld(), (int) Math.floor(l.getX() + nx * 0.7), (int) Math.floor(l.getZ() + nz * 0.7), l.getY());
    }

    /** В клетке (x,z) на уровне ног feet стоит то, что прыжком не взять (забор, стена). */
    public static boolean tallAt(org.bukkit.World w, int x, int z, double feet) {
        int fy = (int) Math.floor(feet + 0.01);
        for (int dy = 0; dy <= 1; dy++) {
            org.bukkit.block.Block b = w.getBlockAt(x, fy + dy, z);
            if (b.isPassable()) continue;
            double top = b.getY();
            for (org.bukkit.util.BoundingBox bb : b.getCollisionShape().getBoundingBoxes()) top = Math.max(top, b.getY() + bb.getMaxY());
            if (top - feet > 1.2) return true;
        }
        return false;
    }

    /** Между ботом и точкой (по прямой, на уровне ног) есть забор или стена выше прыжка. */
    public static boolean tallBetween(Player p, org.bukkit.Location to) {
        org.bukkit.Location l = p.getLocation();
        double dx = to.getX() - l.getX(), dz = to.getZ() - l.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        for (double s = 0.5; s < len - 0.4; s += 0.4) {
            if (tallAt(l.getWorld(), (int) Math.floor(l.getX() + dx / len * s), (int) Math.floor(l.getZ() + dz / len * s), l.getY())) return true;
        }
        return false;
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
