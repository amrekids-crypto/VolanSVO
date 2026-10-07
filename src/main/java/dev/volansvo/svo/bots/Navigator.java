package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.nms.BotNms;
import dev.volansvo.svo.bots.nms.PathProbe;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ведёт бота по пути из PathProbe: выбирает направление на ближайший узел, прыгает на
 * ступеньки, плывёт, открывает двери, замечает застревание и перестраивает путь.
 * Сам ничего не крутит: возвращает желаемое направление, а повороты и ввод делает Bot.
 */
public final class Navigator {

    /** Сколько путей в сумме все боты могут построить за один тик (защита TPS). */
    private static int budgetTick = -1;
    private static int budgetLeft = 0;
    private static int PATHS_PER_TICK = 3;

    /** Сколько путей все боты вместе ищут за тик (меньше при перегрузе сервера). */
    static void setPathsPerTick(int n) { PATHS_PER_TICK = Math.max(1, n); }

    private final PathProbe probe = new PathProbe();

    private List<PathProbe.Step> steps;
    private int idx;
    private boolean reaches;
    private Location goal;
    private int accuracy = 1;

    private int sinceRepath = 0;
    private int stuckTicks = 0;
    private double bestDist = Double.MAX_VALUE;
    private int failures = 0;
    /** Путь может уходить за границу зоны (бот как раз уходит от неё). */
    public boolean allowOutsideZone;
    private int unstuckTicks = 0;
    private float unstuckStrafe = 0f;

    /** Итог шага навигации на текущий тик. */
    public static final class Move {
        public double dx, dz;     // желаемое горизонтальное направление (не нормировано)
        public boolean jump;
        public boolean sprintOk;  // путь прямой, можно бежать
        public float strafeBias;  // боковое смещение для выхода из застревания
        public boolean active;
        /** Куда смотреть при ходьбе: точка на несколько узлов вперёд (без рывков головой). */
        public double lookX, lookZ;
    }

    private final Move move = new Move();

    public Location getGoal() { return goal; }
    public boolean hasPath() { return steps != null && idx < steps.size(); }
    /** Текущий путь доходит до цели (а не обрывается в ближайшей к ней точке). */
    public boolean reaches() { return steps != null && reaches; }
    /** Сколько раз подряд не удалось дойти до текущей цели. */
    public int getFailures() { return failures; }
    /** Сколько тиков подряд бот не приближается к узлу пути. */
    public int stuckTicks() { return stuckTicks; }
    /** Следующий узел пути (null - пути нет). */
    public PathProbe.Step nextStep() { return steps != null && idx < steps.size() ? steps.get(idx) : null; }

    public void clear() {
        steps = null;
        goal = null;
        idx = 0;
        failures = 0;
        stuckTicks = 0;
        bestDist = Double.MAX_VALUE;
    }

    /**
     * Задать цель. Путь перестраивается, только если цель заметно сместилась
     * или старый путь закончился, иначе бот продолжает идти по текущему.
     */
    public void setGoal(Location target, int accuracy) {
        if (target == null) { clear(); return; }
        boolean moved = goal == null || !goal.getWorld().equals(target.getWorld())
            || goal.distanceSquared(target) > 9.0;
        if (moved) {
            failures = 0;
            steps = null;
        }
        this.goal = target.clone();
        this.accuracy = accuracy;
    }

    /** Цель достигнута (по горизонтали в пределах dist, по высоте до 2.5 блоков). */
    public boolean arrived(Player p, double dist) {
        if (goal == null) return true;
        Location l = p.getLocation();
        if (!l.getWorld().equals(goal.getWorld())) return false;
        double dx = l.getX() - goal.getX(), dz = l.getZ() - goal.getZ();
        return dx * dx + dz * dz <= dist * dist && Math.abs(l.getY() - goal.getY()) < 2.5;
    }

    public Move tick(Player p, int serverTick) {
        move.active = false; move.jump = false; move.sprintOk = false; move.strafeBias = 0f;
        move.dx = 0; move.dz = 0; move.lookX = 0; move.lookZ = 0;
        if (goal == null) return move;
        Location pos = p.getLocation();
        if (!pos.getWorld().equals(goal.getWorld())) return move;

        sinceRepath++;
        // Уже на месте: стоим, это не застревание.
        if (arrived(p, Math.max(0.8, accuracy + 0.3))) {
            stuckTicks = 0;
            failures = 0;
            bestDist = Double.MAX_VALUE;
            return move;
        }
        boolean needPath = steps == null || idx >= steps.size();
        if (needPath && sinceRepath >= 10 && takeBudget(serverTick)) {
            repath(p);
        }

        boolean inWater = BotNms.inWater(p);
        boolean onGround = BotNms.onGround(p);

        Vector target;
        int nodeY;
        if (steps != null && idx < steps.size()) {
            advance(pos);
            if (idx >= steps.size()) {
                if (!reaches && !arrived(p, Math.max(1.5, accuracy))) { steps = null; return move; }
                target = goal.toVector();
                nodeY = goal.getBlockY();
            } else {
                PathProbe.Step s = steps.get(idx);
                target = new Vector(s.x + 0.5, s.y, s.z + 0.5);
                nodeY = s.y;
                openDoorIfNeeded(p.getWorld(), s, pos);
            }
        } else {
            // Пути нет (ещё не построен или цель рядом в воздухе) - идём напрямую.
            target = goal.toVector();
            nodeY = goal.getBlockY();
        }

        double dx = target.getX() - pos.getX();
        double dz = target.getZ() - pos.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        move.dx = dx; move.dz = dz;
        // Взгляд: на узел через 3 вперёд (или на цель), чтобы голова не дёргалась на каждом блоке.
        Vector look = target;
        if (steps != null && idx < steps.size()) {
            PathProbe.Step ahead = steps.get(Math.min(steps.size() - 1, idx + 3));
            look = new Vector(ahead.x + 0.5, ahead.y, ahead.z + 0.5);
        }
        move.lookX = look.getX() - pos.getX();
        move.lookZ = look.getZ() - pos.getZ();
        if (move.lookX * move.lookX + move.lookZ * move.lookZ < 0.36) { move.lookX = dx; move.lookZ = dz; }
        move.active = flat > 0.05 || Math.abs(nodeY - pos.getY()) > 0.6;

        // Прыжок: следующий узел выше, или упёрлись в стену, стоя на земле.
        if (onGround && ((nodeY > pos.getY() + 0.5 && flat < 2.0) || BotNms.horizontalCollision(p))) {
            move.jump = true;
        }
        // В воде держимся на плаву и всплываем к узлу.
        if (inWater && nodeY >= pos.getY() - 0.4) move.jump = true;

        move.sprintOk = isStraight() && !inWater;

        // Застревание: расстояние до узла не уменьшается.
        double d = flat + Math.abs(nodeY - pos.getY()) * 0.5;
        if (d < bestDist - 0.08) { bestDist = d; stuckTicks = 0; }
        else stuckTicks++;

        if (unstuckTicks > 0) {
            unstuckTicks--;
            move.strafeBias = unstuckStrafe;
            move.jump = move.jump || onGround;
        } else if (stuckTicks > 25) {
            unstuckTicks = 12;
            unstuckStrafe = ThreadLocalRandom.current().nextBoolean() ? 1f : -1f;
        }
        if (stuckTicks > 70) {
            stuckTicks = 0;
            bestDist = Double.MAX_VALUE;
            failures++;
            steps = null; // перестроим на следующем тике
        }
        return move;
    }

    private void repath(Player p) {
        sinceRepath = 0;
        PathProbe.Result r = probe.find(p, goal.getBlockX(), goal.getBlockY(), goal.getBlockZ(), accuracy, 64f);
        stuckTicks = 0;
        bestDist = Double.MAX_VALUE;
        if (r == null) {
            failures++;
            steps = null;
            return;
        }
        // Путь, который уходит в зону (бот сам внутри), не берём - обходим. Ненадолго выйти
        // за край (обогнуть дом у границы) можно.
        org.bukkit.WorldBorder wb = p.getWorld().getWorldBorder();
        if (!allowOutsideZone && wb.isInside(p.getLocation())) {
            double half = wb.getSize() / 2, cx = wb.getCenter().getX(), cz = wb.getCenter().getZ();
            int outside = 0;
            boolean deep = false;
            for (PathProbe.Step st : r.steps) {
                double out = Math.max(Math.abs(st.x + 0.5 - cx), Math.abs(st.z + 0.5 - cz)) - half;
                if (out > 0) outside++;
                if (out > 4) deep = true;
            }
            if (deep || outside > 12) {
                failures++;
                steps = null;
                return;
            }
        }
        steps = r.steps;
        reaches = r.reaches;
        idx = 0;
        // Первый узел - это клетка, где бот уже стоит.
        if (steps.size() > 1) idx = 1;
        if (!reaches && steps.size() <= 2) failures++;
    }

    /** Сдвигает указатель пути, когда бот дошёл до узла или срезал угол. */
    private void advance(Location pos) {
        while (idx < steps.size()) {
            PathProbe.Step s = steps.get(idx);
            double dx = s.x + 0.5 - pos.getX(), dz = s.z + 0.5 - pos.getZ();
            double dy = s.y - pos.getY();
            boolean close = dx * dx + dz * dz < 0.42 * 0.42 && dy > -1.5 && dy < 1.0;
            boolean skip = false;
            if (!close && idx + 1 < steps.size()) {
                // Если следующий узел на той же высоте и бот уже ближе к нему, чем этот узел, -
                // текущий можно пропустить (срез угла без застревания на рёбрах блоков).
                PathProbe.Step n = steps.get(idx + 1);
                if (n.y == s.y && Math.abs(dy) < 0.6) {
                    double ndx = n.x + 0.5 - pos.getX(), ndz = n.z + 0.5 - pos.getZ();
                    double sn = (n.x - s.x) * (n.x - s.x) + (n.z - s.z) * (n.z - s.z);
                    skip = ndx * ndx + ndz * ndz < sn;
                }
            }
            if (!close && !skip) return;
            idx++;
            bestDist = Double.MAX_VALUE;
            stuckTicks = 0;
        }
    }

    /** Прямой участок: три следующих узла на одной высоте и почти на одной линии. */
    private boolean isStraight() {
        if (steps == null || idx + 2 >= steps.size()) return goal != null;
        PathProbe.Step a = steps.get(idx), b = steps.get(idx + 1), c = steps.get(idx + 2);
        if (a.y != b.y || b.y != c.y) return false;
        int d1x = b.x - a.x, d1z = b.z - a.z, d2x = c.x - b.x, d2z = c.z - b.z;
        return d1x == d2x && d1z == d2z;
    }

    private void openDoorIfNeeded(World w, PathProbe.Step s, Location pos) {
        double dx = s.x + 0.5 - pos.getX(), dz = s.z + 0.5 - pos.getZ();
        if (dx * dx + dz * dz > 2.5 * 2.5) return;
        for (int dy = 0; dy <= 1; dy++) {
            Block b = w.getBlockAt(s.x, s.y + dy, s.z);
            BlockData data = b.getBlockData();
            if (!(data instanceof Openable)) continue;
            String type = b.getType().name();
            if (type.startsWith("IRON_")) continue; // железные руками не открыть
            if (!type.endsWith("_DOOR") && !type.endsWith("_FENCE_GATE") && !(dy == 1 && type.endsWith("_TRAPDOOR"))) continue;
            Openable o = (Openable) data;
            if (o.isOpen()) continue;
            o.setOpen(true);
            b.setBlockData(o, true);
            w.playSound(b.getLocation(), type.endsWith("_GATE") ? Sound.BLOCK_FENCE_GATE_OPEN
                : Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);
            return;
        }
    }

    private static boolean takeBudget(int serverTick) {
        if (budgetTick != serverTick) { budgetTick = serverTick; budgetLeft = PATHS_PER_TICK; }
        if (budgetLeft <= 0) return false;
        budgetLeft--;
        return true;
    }
}
