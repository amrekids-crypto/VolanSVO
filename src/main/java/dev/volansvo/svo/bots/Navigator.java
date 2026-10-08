package dev.volansvo.svo.bots;

import dev.volansvo.svo.bots.nav.Cell;
import dev.volansvo.svo.bots.nav.CostField;
import dev.volansvo.svo.bots.nav.NavGoal;
import dev.volansvo.svo.bots.nav.PathSearch;
import dev.volansvo.svo.bots.nav.PathStep;
import dev.volansvo.svo.bots.nav.Pos;
import dev.volansvo.svo.bots.nav.Trails;
import dev.volansvo.svo.bots.nms.BotNms;
import dev.volansvo.svo.bots.nms.NmsBlockView;
import dev.volansvo.svo.bots.nms.PathProbe;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Ведёт бота к цели. Путь строит {@link PathSearch}: он знает прыжки через ямы, спуски,
 * лестницы, воду и умеет закладывать в путь сломать блок или поставить его под ноги.
 * Если он пути не нашёл или бот на нём застрял - запасной ванильный поиск мобов.
 *
 * Сам ничего не крутит и не ломает: отдаёт на тик направление, прыжок и просьбу
 * сломать/поставить блок, исполняет это Bot.
 */
public final class Navigator {

    // ---- общий на всех ботов бюджет поиска пути на тик
    private static long budgetNs = 1_500_000L;
    private static int budgetTick = -1;
    private static long spentNs;
    private static int askers, askersPrev = 1;
    private static int vanillaLeft;
    private static int PATHS_PER_TICK = 3;

    /** Сколько ванильных путей все боты вместе ищут за тик. */
    static void setPathsPerTick(int n) { PATHS_PER_TICK = Math.max(1, n); }

    /** Сколько миллисекунд тика все боты вместе тратят на поиск пути. */
    static void setBudgetMs(double ms) { budgetNs = (long) (Math.max(0.2, ms) * 1e6); }

    private static void newTick(int tick) {
        if (budgetTick == tick) return;
        budgetTick = tick;
        spentNs = 0;
        askersPrev = Math.max(1, askers);
        askers = 0;
        vanillaLeft = PATHS_PER_TICK;
    }

    /** Доля бюджета этому боту: поровну между теми, кто искал путь в прошлом тике. */
    private static long grant(int tick) {
        newTick(tick);
        askers++;
        long left = budgetNs - spentNs;
        if (left < 30_000L) return 0;
        return Math.min(left, Math.max(200_000L, budgetNs / askersPrev));
    }

    /** Тропы живых игроков на текущей карте (null - не ведутся). */
    private static Trails trails;

    static void setTrails(Trails t) { trails = t; }

    private static boolean takeVanilla(int tick) {
        newTick(tick);
        if (vanillaLeft <= 0) return false;
        vanillaLeft--;
        return true;
    }

    /** Итог шага навигации на текущий тик. */
    public static final class Move {
        public double dx, dz;     // желаемое горизонтальное направление (не нормировано)
        public boolean jump;
        public boolean sprintOk;  // путь прямой, можно бежать
        public boolean sprintMust; // разбег перед прыжком через яму
        public float strafeBias;  // боковое смещение для выхода из застревания
        public boolean active;
        /** Куда смотреть при ходьбе: точка впереди по пути (без рывков головой). */
        public double lookX, lookZ;
        /** Наклон головы при ходьбе: на землю в десятке блоков впереди. */
        public float lookPitch;
        /** Идём по пути со спланированными действиями: Bot сам преграды не ковыряет. */
        public boolean planned;
        /** Блок, который надо сломать, чтобы пройти дальше. */
        public Block mine;
        /** Куда поставить блок, чтобы пройти дальше. */
        public Block place;
        /** На какую высоту ног подняться столбом под собой (MIN_VALUE - не строить). */
        public int pillarTo = Integer.MIN_VALUE;
        /** Впереди или здесь низкий проход: идём присев. */
        public boolean crouch;
    }

    private final BotSkill skill;
    private final BooleanSupplier placeBlocked;
    private final PathProbe probe = new PathProbe();
    private final Move move = new Move();
    private final LongOpenHashSet noBreak = new LongOpenHashSet();
    private int noBreakClearAt;
    private NmsBlockView live;

    private List<PathStep> steps;
    private int idx;
    private boolean reaches;
    /** Путь построен своим поиском (false - ванильным). */
    private boolean smart;
    private Location goal;
    private int accuracy = 1;
    private boolean modify = true;

    private PathSearch search;
    private int searchStart;
    private boolean replan;
    private int smartFails, noPlaceUntil, noParkourUntil;
    /** Места, которые путь обходит: тройки x, z, радиус. */
    private double[] avoid = new double[0];

    private int sinceRepath = 0;
    private int stuckTicks = 0;
    private double bestDist = Double.MAX_VALUE;
    private long lastTarget = Pos.NONE;
    private int failures = 0;
    /** Путь может уходить за границу зоны (бот как раз уходит от неё). */
    public boolean allowOutsideZone;
    /** Финал: к центру любой ценой, путь смелее ломает и ставит блоки. */
    public boolean eager;
    private int unstuckTicks = 0;
    private float unstuckStrafe = 0f;

    private int visIdx = -1, visAt;
    private boolean hopping;
    private int hopDecideAt;
    private int parkourIdx = -1, parkourFalls;
    private boolean backing, runupDone;
    private int placeTicks;
    private long workCell = Pos.NONE;
    private int workTries;

    Navigator(BotSkill skill, BooleanSupplier placeBlocked) {
        this.skill = skill;
        this.placeBlocked = placeBlocked;
    }

    public Location getGoal() { return goal; }
    public boolean hasPath() { return steps != null && idx < steps.size(); }
    /** Текущий путь доходит до цели (а не обрывается в ближайшей к ней точке). */
    public boolean reaches() { return steps != null && reaches; }
    /** Сколько узлов пути осталось пройти (-1 - пути нет). */
    public int remaining() { return steps == null ? -1 : steps.size() - idx; }
    /** Сколько раз подряд не удалось дойти до текущей цели. */
    public int getFailures() { return failures; }
    /** Сколько тиков подряд бот не приближается к узлу пути. */
    public int stuckTicks() { return stuckTicks; }

    public void clear() {
        steps = null;
        search = null;
        replan = false;
        goal = null;
        idx = 0;
        failures = 0;
        smartFails = 0;
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
            smartFails = 0;
            search = null;
            // Цель отошла недалеко (погоня, союзник): идём по старому пути, пока строится новый.
            boolean near = steps != null && smart && goal != null && goal.getWorld().equals(target.getWorld())
                && goal.distanceSquared(target) < 144.0;
            if (near) replan = true; else steps = null;
        }
        this.goal = target.clone();
        this.accuracy = accuracy;
    }

    /** Разрешить пути ломать и ставить блоки (в бою и бегстве - нет: некогда). */
    void setModify(boolean on) {
        if (modify == on) return;
        modify = on;
        search = null;
        if (!on && steps != null && smart) {
            for (int i = idx; i < steps.size(); i++) {
                if (steps.get(i).works()) { steps = null; break; }
            }
        }
    }

    /** Места, которые пути обходят стороной (логово босса, где только что убили): тройки x, z, радиус. */
    void setAvoid(double[] xzr) {
        avoid = xzr;
    }

    /**
     * Высота, на которой можно встать в столбце (x,z), ближайшая к near: под крышей - пол,
     * а не крыша. MIN_VALUE - в пределах 8 блоков встать негде.
     */
    int standY(World w, int x, int z, int near) {
        if (live == null || !live.sameWorld(w)) live = new NmsBlockView(w, false, null, null);
        for (int d = 0; d <= 8; d++) {
            if (Cell.stand(live, x, near - d, z)) return near - d;
            if (d > 0 && Cell.stand(live, x, near + d, z)) return near + d;
        }
        return Integer.MIN_VALUE;
    }

    /** Блок из пути сломать не вышло: второй раз подряд - больше через него не планируем. */
    void workFailed(Block b) {
        long k = Pos.pack(b.getX(), b.getY(), b.getZ());
        if (k == workCell) workTries++; else { workCell = k; workTries = 1; }
        if (workTries >= 2) {
            noBreak.add(k);
            steps = null;
            search = null;
            workTries = 0;
        }
    }

    /** Блок не ставится (запрет на карте, нет опоры): минуту строим пути без мостиков и столбов. */
    void placeFailed(int tick) {
        noPlaceUntil = tick + 20 * 60;
        placeTicks = 0;
        steps = null;
        search = null;
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
        Move m = move;
        m.active = false; m.jump = false; m.sprintOk = false; m.sprintMust = false; m.strafeBias = 0f;
        m.dx = 0; m.dz = 0; m.lookX = 0; m.lookZ = 0; m.lookPitch = 0f;
        m.planned = false; m.mine = null; m.place = null; m.pillarTo = Integer.MIN_VALUE; m.crouch = false;
        if (goal == null) return m;
        Location pos = p.getLocation();
        if (!pos.getWorld().equals(goal.getWorld())) return m;

        sinceRepath++;
        // Уже на месте: стоим, это не застревание. Но пока путь ведёт вверх (лестница под
        // целью на крыше), не на месте: иначе бот у верха отпускал прыжок и сползал.
        boolean stillUp = false;
        if (hasPath() && goal.getY() > pos.getY() + 1.0) {
            for (int i = idx; i < steps.size() && !stillUp; i++) stillUp = steps.get(i).y > pos.getY() + 0.6;
        }
        if (!stillUp && arrived(p, Math.max(0.8, accuracy + 0.3))) {
            stuckTicks = 0;
            failures = 0;
            bestDist = Double.MAX_VALUE;
            search = null;
            return m;
        }
        if (live == null || !live.sameWorld(pos.getWorld())) live = new NmsBlockView(pos.getWorld(), false, null, null);
        live.newTick();
        if (serverTick >= noBreakClearAt) { noBreak.clear(); noBreakClearAt = serverTick + 20 * 60; }

        boolean inWater = BotNms.inWater(p);
        boolean onGround = BotNms.onGround(p);
        plan(p, pos, serverTick, onGround, inWater);

        double tx, tz;
        int nodeY;
        PathStep s = null;
        boolean run = false;
        if (hasPath()) {
            advance(pos, onGround);
            if (idx >= steps.size()) {
                if (!reaches && !arrived(p, Math.max(1.5, accuracy))) { steps = null; return m; }
                tx = goal.getX(); tz = goal.getZ();
                nodeY = goal.getBlockY();
            } else {
                s = steps.get(idx);
                if (smart) {
                    // Мир изменился (взрыв, чужая стройка): клетка пути больше не годится.
                    if (!s.works() && s.move != PathStep.PARKOUR && !Cell.stand(live, s.x, s.y, s.z)
                            && !(s.move == PathStep.CROUCH && Cell.crouch(live, s.x, s.y, s.z))) {
                        steps = null;
                        return m;
                    }
                    if (work(p, s, pos, onGround, serverTick)) return m;
                    if (s.move == PathStep.PARKOUR && idx > 0) {
                        parkour(p, pos, s, onGround, serverTick);
                        return m;
                    }
                }
                tx = s.x + 0.5; tz = s.z + 0.5;
                nodeY = s.y;
                openDoorIfNeeded(p, s, pos);
                if (smart && plain(s)) {
                    int vis = lookahead(pos, s, serverTick);
                    if (vis > idx) {
                        PathStep far = steps.get(vis);
                        // Узлы, оставшиеся позади прямой, снимаем.
                        while (idx < vis) {
                            PathStep a = steps.get(idx);
                            double bd = sq(far.x + 0.5 - pos.getX()) + sq(far.z + 0.5 - pos.getZ());
                            double ad = sq(far.x - a.x) + sq(far.z - a.z);
                            if (bd < ad) idx++; else break;
                        }
                        s = steps.get(idx);
                        tx = far.x + 0.5; tz = far.z + 0.5;
                        run = true;
                    }
                }
            }
        } else {
            // Пути нет (ещё не построен или цель рядом в воздухе) - идём напрямую.
            tx = goal.getX(); tz = goal.getZ();
            nodeY = goal.getBlockY();
        }

        double dx = tx - pos.getX();
        double dz = tz - pos.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        m.dx = dx; m.dz = dz;
        m.planned = smart && s != null;

        // Взгляд: по прямой, которой бежим, либо на узел через 3 вперёд, чтобы голова не дёргалась.
        double lx = dx, lz = dz;
        if (!run && steps != null && idx < steps.size()) {
            PathStep ahead = steps.get(Math.min(steps.size() - 1, idx + 3));
            lx = ahead.x + 0.5 - pos.getX();
            lz = ahead.z + 0.5 - pos.getZ();
            if (lx * lx + lz * lz < 0.36) { lx = dx; lz = dz; }
        }
        m.lookX = lx; m.lookZ = lz;
        m.lookPitch = (float) Math.toDegrees(Math.atan2(1.62 + pos.getY() - nodeY, Math.max(flat, 12.0)));
        m.active = flat > 0.05 || Math.abs(nodeY - pos.getY()) > 0.6;

        double sFlat = s == null ? flat : Math.sqrt(sq(s.x + 0.5 - pos.getX()) + sq(s.z + 0.5 - pos.getZ()));
        // Прыжок: следующий узел выше, или упёрлись в стену, стоя на земле.
        if (onGround && ((nodeY > pos.getY() + 0.5 && sFlat < 2.0) || BotNms.horizontalCollision(p))) m.jump = true;
        // В воде держимся на плаву и всплываем к узлу.
        if (inWater && nodeY >= pos.getY() - 0.4) m.jump = true;
        // На лестнице вверх лезут с зажатым прыжком. Держим его, пока узел не ниже нас: отпустишь
        // между узлами - бот съезжает и топчется внизу.
        boolean onLadder = Cell.kind(live.type(pos.getBlockX(), (int) Math.floor(pos.getY() + 0.2), pos.getBlockZ())) == Cell.CLIMB;
        if (!inWater && onLadder && (nodeY > pos.getY() + 0.3 || s != null && s.move == PathStep.CLIMB && s.y >= pos.getY() - 0.2)) m.jump = true;

        // Низкий проход здесь или в паре шагов: приседаем заранее, иначе упрёмся головой (и прыгнем).
        if (smart && steps != null && idx < steps.size()) {
            for (int i = Math.max(0, idx - 1); i < Math.min(steps.size(), idx + 2) && !m.crouch; i++) {
                PathStep c = steps.get(i);
                if (c.move == PathStep.CROUCH && sq(c.x + 0.5 - pos.getX()) + sq(c.z + 0.5 - pos.getZ()) < 2.2 * 2.2) m.crouch = true;
            }
            if (m.crouch) m.jump = false;
        }
        boolean drop = s != null && s.move == PathStep.DESCEND && pos.getY() - s.y > 1.5;
        m.sprintOk = !inWater && !drop && (run || isStraight());

        // На длинной прямой бежим прыжками, как игроки.
        if (run && onGround && !m.jump && flat >= 5.0 && skill.sprintJump && hopLeg(serverTick)
                && p.getFoodLevel() > 8 && headroomAhead(pos, dx / flat, dz / flat, s.y)) m.jump = true;

        // Застревание: расстояние до цели движения не уменьшается.
        long tk = Pos.pack((int) Math.floor(tx), nodeY, (int) Math.floor(tz));
        if (tk != lastTarget) { lastTarget = tk; bestDist = Double.MAX_VALUE; }
        double d = flat + Math.abs(nodeY - pos.getY()) * 0.5;
        if (d < bestDist - 0.08) { bestDist = d; stuckTicks = 0; }
        else stuckTicks++;

        if (unstuckTicks > 0) {
            unstuckTicks--;
            m.strafeBias = unstuckStrafe;
            m.jump = m.jump || onGround;
        } else if (stuckTicks > (smart ? 20 : 25)) {
            unstuckTicks = 12;
            unstuckStrafe = ThreadLocalRandom.current().nextBoolean() ? 1f : -1f;
        }
        if (stuckTicks > (smart ? 45 : 70)) {
            stuckTicks = 0;
            bestDist = Double.MAX_VALUE;
            failures++;
            if (smart) smartFails++;
            steps = null; // перестроим на следующем тике
        }
        return m;
    }

    // ================================================================== построение пути

    private void plan(Player p, Location pos, int tick, boolean onGround, boolean inWater) {
        boolean needPath = !hasPath();
        boolean nearEnd = !needPath && !reaches && steps.size() - idx <= 5;
        if (search == null && (needPath || nearEnd || replan) && sinceRepath >= (needPath ? 6 : (replan ? 10 : 25))) {
            if (skill.navSmart && smartFails < 2) startSearch(p, pos, tick, onGround, inWater);
            else if (needPath && sinceRepath >= 10 && takeVanilla(tick)) repathVanilla(p);
        }
        if (search == null) return;
        long g = grant(tick);
        PathSearch.State st = PathSearch.State.RUNNING;
        if (g > 0) {
            long t0 = System.nanoTime();
            st = search.step(t0 + g);
            spentNs += System.nanoTime() - t0;
        }
        // Долго не досчитали - берём путь до ближайшей к цели клетки.
        if (st == PathSearch.State.RUNNING && tick - searchStart > 60) st = search.finishNow();
        if (st != PathSearch.State.RUNNING) adopt(pos, st);
    }

    private void startSearch(Player p, Location pos, int tick, boolean onGround, boolean inWater) {
        World w = pos.getWorld();
        NmsBlockView view = new NmsBlockView(w, true, tools(p), noBreak);
        int sx = pos.getBlockX(), sy = (int) Math.floor(pos.getY() + 0.2), sz = pos.getBlockZ();
        // В воздухе ищем от клетки, куда приземлимся.
        if (!onGround && !inWater) {
            int y = sy;
            for (int k = 0; k < 6 && !Cell.stand(view, sx, y, sz); k++) y--;
            if (Cell.stand(view, sx, y, sz)) sy = y;
        }
        PathSearch.Options o = new PathSearch.Options();
        o.dig = (modify || eager) && skill.navDig;
        o.blocks = (modify || eager) && skill.navPlace && tick >= noPlaceUntil && !placeBlocked.getAsBoolean()
            ? Math.min(eager ? 24 : 10, Math.max(0, Builder.blockCount(p) - (eager ? 2 : 4))) : 0;
        o.parkourGap = skill.navParkour && tick >= noParkourUntil ? (modify ? 2 : 1) : 0;
        search = new PathSearch(view, field(w, pos), new NavGoal.Near(goal.getBlockX(), goal.getBlockY(), goal.getBlockZ(), accuracy),
            o, sx, sy, sz);
        searchStart = tick;
        sinceRepath = 0;
    }

    /** Цена клеток: за границу зоны не ходим, у самого края и возле босса - неохотно. */
    private CostField field(World w, Location pos) {
        WorldBorder wb = w.getWorldBorder();
        final double cx = wb.getCenter().getX(), cz = wb.getCenter().getZ(), half = wb.getSize() / 2.0;
        // Уходим от зоны - путь через её край можно.
        final boolean inside = wb.isInside(pos) && !allowOutsideZone;
        final double[] av = avoid;
        final Trails tr = trails;
        return (x, y, z) -> {
            double c = 0;
            if (inside) {
                double edge = half - Math.max(Math.abs(x + 0.5 - cx), Math.abs(z + 0.5 - cz));
                // Ненадолго выйти за край (обогнуть дом у границы) можно, глубоко в зону - нет.
                if (edge < -4) return CostField.BLOCKED;
                if (edge < 0.5) c += 12;
                else if (edge < 4) c += (4 - edge) * 3;
            }
            for (int i = 0; i + 2 < av.length; i += 3) {
                if (sq(x + 0.5 - av[i]) + sq(z + 0.5 - av[i + 1]) < av[i + 2] * av[i + 2]) { c += 25; break; }
            }
            if (tr != null) c -= tr.bonus(x, y, z);
            return c;
        };
    }

    private void adopt(Location pos, PathSearch.State st) {
        PathSearch done = search;
        search = null;
        replan = false;
        sinceRepath = 0;
        if (st == PathSearch.State.FAILED) {
            // Идти некуда даже на шаг: дальше для этой цели пробуем ванильный поиск.
            smartFails = 2;
            sinceRepath = 10;
            return;
        }
        List<PathStep> list = done.path();
        steps = list;
        reaches = st == PathSearch.State.FOUND;
        smart = true;
        // Пока считали, бот ушёл от стартовой клетки: начинаем с ближайшего узла.
        int near = 0;
        double nd = Double.MAX_VALUE;
        for (int i = 0; i < Math.min(6, list.size()); i++) {
            PathStep s = list.get(i);
            if (Math.abs(s.y - pos.getY()) > 1.5) continue;
            double d = sq(s.x + 0.5 - pos.getX()) + sq(s.z + 0.5 - pos.getZ());
            if (d < nd) { nd = d; near = i; }
        }
        idx = Math.min(near + 1, list.size());
        // Короткий обрывок без работ - идти некуда. Если он копает или строит, это продвижение.
        if (!reaches && list.size() <= 2 && !list.get(list.size() - 1).works()) failures++;
        stuckTicks = 0;
        bestDist = Double.MAX_VALUE;
        visIdx = -1;
        parkourIdx = -1;
        placeTicks = 0;
    }

    private void repathVanilla(Player p) {
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
        WorldBorder wb = p.getWorld().getWorldBorder();
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
        List<PathStep> list = new ArrayList<PathStep>(r.steps.size());
        for (PathProbe.Step st : r.steps) list.add(new PathStep(st.x, st.y, st.z));
        steps = list;
        reaches = r.reaches;
        smart = false;
        idx = 0;
        // Первый узел - это клетка, где бот уже стоит.
        if (steps.size() > 1) idx = 1;
        if (!reaches && steps.size() <= 2) failures++;
    }

    /** Лучший материал инструмента каждого вида в инвентаре (-1 - нет такого). */
    private static int[] tools(Player p) {
        int[] t = {-1, -1, -1, -1};
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null) continue;
            String n = it.getType().name();
            int kind = n.endsWith("_PICKAXE") ? NmsBlockView.PICKAXE : n.endsWith("_AXE") ? NmsBlockView.AXE
                : n.endsWith("_SHOVEL") ? NmsBlockView.SHOVEL : n.endsWith("_HOE") ? NmsBlockView.HOE : -1;
            if (kind < 0) continue;
            int tier = n.startsWith("NETHERITE") ? 5 : n.startsWith("DIAMOND") ? 4 : n.startsWith("IRON") ? 3
                : n.startsWith("STONE") ? 2 : n.startsWith("GOLDEN") ? 1 : 0;
            if (tier > t[kind]) t[kind] = tier;
        }
        return t;
    }

    // ================================================================== следование

    /** Сдвигает указатель пути, когда бот дошёл до узла или срезал угол. */
    private void advance(Location pos, boolean onGround) {
        while (idx < steps.size()) {
            PathStep s = steps.get(idx);
            double dx = s.x + 0.5 - pos.getX(), dz = s.z + 0.5 - pos.getZ();
            double dy = s.y - pos.getY();
            double flat2 = dx * dx + dz * dz;
            boolean close;
            switch (s.move) {
                case PathStep.CLIMB: case PathStep.SWIM: case PathStep.DIG_DOWN:
                    // Узел прямо над или под нами: дошли, только когда сравнялись по высоте.
                    close = flat2 < 0.6 * 0.6 && Math.abs(dy) < 0.45;
                    break;
                case PathStep.PILLAR:
                    close = flat2 < 0.7 * 0.7 && dy < 0.06 && dy > -1.0 && onGround;
                    break;
                case PathStep.PARKOUR:
                    close = onGround && flat2 < 0.6 * 0.6 && Math.abs(dy) < 0.6;
                    break;
                default:
                    close = flat2 < 0.42 * 0.42 && dy > -1.5 && dy < 1.0;
            }
            boolean skip = false;
            if (!close && idx + 1 < steps.size() && s.move <= PathStep.DIAGONAL && !s.works()) {
                // Если следующий узел на той же высоте и бот уже ближе к нему, чем этот узел, -
                // текущий можно пропустить (срез угла без застревания на рёбрах блоков).
                PathStep n = steps.get(idx + 1);
                if (n.y == s.y && Math.abs(dy) < 0.6 && n.move <= PathStep.DIAGONAL && !n.works()) {
                    double ndx = n.x + 0.5 - pos.getX(), ndz = n.z + 0.5 - pos.getZ();
                    double sn = (n.x - s.x) * (n.x - s.x) + (n.z - s.z) * (n.z - s.z);
                    skip = ndx * ndx + ndz * ndz < sn;
                }
            }
            if (!close && !skip) return;
            idx++;
            bestDist = Double.MAX_VALUE;
            stuckTicks = 0;
            placeTicks = 0;
        }
    }

    /**
     * Шаг пути требует сломать или поставить блок: просим об этом Bot и стоим.
     * false - работы нет (или надо сначала подойти), идём дальше.
     */
    private boolean work(Player p, PathStep s, Location pos, boolean onGround, int tick) {
        World w = pos.getWorld();
        if (s.breaks != null) {
            Location eye = p.getEyeLocation();
            for (long b : s.breaks) {
                int bx = Pos.x(b), by = Pos.y(b), bz = Pos.z(b);
                byte t = live.type(bx, by, bz);
                boolean feet = bx == s.x && by == s.y && bz == s.z;
                if (feet ? Cell.feetFree(t) : Cell.bodyFree(t)) continue;
                if (sq(bx + 0.5 - eye.getX()) + sq(by + 0.5 - eye.getY()) + sq(bz + 0.5 - eye.getZ()) > 4.2 * 4.2) return false;
                move.mine = w.getBlockAt(bx, by, bz);
                hold();
                return true;
            }
        }
        if (s.move == PathStep.BRIDGE) {
            int bx = Pos.x(s.place), by = Pos.y(s.place), bz = Pos.z(s.place);
            int k = Cell.kind(live.type(bx, by, bz));
            if (k == Cell.AIR || k == Cell.WATER) {
                if (onGround) {
                    if (++placeTicks > 40) { placeFailed(tick); return true; }
                    move.place = w.getBlockAt(bx, by, bz);
                }
                hold();
                return true;
            }
        } else if (s.move == PathStep.PILLAR) {
            if (pos.getY() < s.y - 0.05 || !onGround) {
                if (++placeTicks > 120) { placeFailed(tick); return true; }
                move.pillarTo = s.y;
                hold();
                return true;
            }
        }
        return false;
    }

    private void hold() {
        move.active = true;
        move.planned = true;
        stuckTicks = 0;
    }

    /**
     * Прыжок через яму: встать на ось прыжка, для двух клеток - отойти на шаг для разбега,
     * бежать и оттолкнуться у самого края.
     */
    private void parkour(Player p, Location pos, PathStep s, boolean onGround, int tick) {
        Move m = move;
        PathStep from = steps.get(idx - 1);
        if (parkourIdx != idx) { parkourIdx = idx; backing = false; runupDone = false; }
        int ddx = Integer.signum(s.x - from.x), ddz = Integer.signum(s.z - from.z);
        int gap = Math.abs(s.x - from.x) + Math.abs(s.z - from.z) - 1;
        double cx = from.x + 0.5, cz = from.z + 0.5;
        double ox = pos.getX() - cx, oz = pos.getZ() - cz;
        double along = ox * ddx + oz * ddz;
        double lat = Math.abs(ox * ddz) + Math.abs(oz * ddx);
        if (pos.getY() < from.y - 1.3) {
            // Не допрыгнули. Второй раз подряд - минуту ходим без прыжков через ямы.
            steps = null;
            if (++parkourFalls >= 2) { parkourFalls = 0; noParkourUntil = tick + 20 * 60; }
            return;
        }
        m.active = true;
        m.planned = true;
        m.lookX = s.x + 0.5 - pos.getX();
        m.lookZ = s.z + 0.5 - pos.getZ();
        m.lookPitch = 12f;
        m.dx = m.lookX; m.dz = m.lookZ;
        stuckTicks++;
        if (stuckTicks > 60) { stuckTicks = 0; failures++; smartFails++; steps = null; return; }
        if (!onGround || along >= 0.8) {
            m.sprintOk = true; m.sprintMust = true; // летим или уже на той стороне
            return;
        }
        if (lat > 0.22 && along < 0.2) {
            m.dx = -ox; m.dz = -oz; // к центру блока, с которого прыгаем
            return;
        }
        if (gap >= 2 && !runupDone && !backing) {
            Vector v = p.getVelocity();
            if (Math.hypot(v.getX(), v.getZ()) < 0.17 && along > -0.7) backing = true; else runupDone = true;
        }
        if (backing) {
            if (along <= -0.9 || !Cell.stand(live, from.x - ddx, from.y, from.z - ddz)) { backing = false; runupDone = true; }
            else { m.dx = -ddx; m.dz = -ddz; return; }
        }
        m.sprintOk = true; m.sprintMust = true;
        m.jump = along >= 0.3;
    }

    /** Обычный шаг пешком без дверей и работ: через такие можно идти по прямой, срезая. */
    private boolean plain(PathStep s) {
        return s.move <= PathStep.DIAGONAL && !s.works()
            && Cell.kind(live.type(s.x, s.y, s.z)) != Cell.DOOR && Cell.kind(live.type(s.x, s.y + 1, s.z)) != Cell.DOOR;
    }

    /**
     * Самый дальний узел, до которого можно дойти по прямой. Игрок не обходит центры блоков,
     * а бежит напрямик; так же бот срезает зигзаги пути.
     */
    private int lookahead(Location pos, PathStep s, int tick) {
        if (visIdx >= idx && visIdx < steps.size() && tick - visAt < 3) return visIdx;
        visAt = tick;
        visIdx = idx;
        if (Math.abs(pos.getY() - s.y) > 0.7) return idx;
        int max = Math.min(steps.size() - 1, idx + 8), end = idx;
        while (end < max && steps.get(end + 1).y == s.y && plain(steps.get(end + 1))) end++;
        for (int j = end; j > idx; j -= (j - idx > 3 ? 2 : 1)) {
            if (clearLine(pos, steps.get(j), s.y)) { visIdx = j; break; }
        }
        return visIdx;
    }

    /** По прямой от бота до узла везде можно стоять (с запасом на ширину тела). */
    private boolean clearLine(Location pos, PathStep to, int y) {
        double x0 = pos.getX(), z0 = pos.getZ();
        double dx = to.x + 0.5 - x0, dz = to.z + 0.5 - z0;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.5) return true;
        double ux = dx / len, uz = dz / len, px = -uz * 0.31, pz = ux * 0.31;
        for (double d = 0.4; d < len; d += 0.45) {
            double x = x0 + ux * d, z = z0 + uz * d;
            if (!Cell.stand(live, (int) Math.floor(x + px), y, (int) Math.floor(z + pz))) return false;
            if (!Cell.stand(live, (int) Math.floor(x - px), y, (int) Math.floor(z - pz))) return false;
        }
        return true;
    }

    /** Над головой на ближайших клетках по ходу есть место для прыжка. */
    private boolean headroomAhead(Location pos, double ux, double uz, int y) {
        for (int k = 0; k <= 3; k++) {
            if (!Cell.bodyFree(live.type((int) Math.floor(pos.getX() + ux * k), y + 2, (int) Math.floor(pos.getZ() + uz * k)))) return false;
        }
        return true;
    }

    /** Бежать ли сейчас прыжками: решается на несколько секунд, у каждого бота своя привычка. */
    private boolean hopLeg(int tick) {
        if (tick >= hopDecideAt) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            hopping = r.nextDouble() < skill.hop;
            hopDecideAt = tick + 50 + r.nextInt(60);
        }
        return hopping;
    }

    /** Прямой участок: три следующих узла на одной высоте и почти на одной линии. */
    private boolean isStraight() {
        if (steps == null || idx + 2 >= steps.size()) return goal != null;
        PathStep a = steps.get(idx), b = steps.get(idx + 1), c = steps.get(idx + 2);
        if (a.y != b.y || b.y != c.y) return false;
        int d1x = b.x - a.x, d1z = b.z - a.z, d2x = c.x - b.x, d2z = c.z - b.z;
        return d1x == d2x && d1z == d2z;
    }

    private void openDoorIfNeeded(Player p, PathStep s, Location pos) {
        World w = pos.getWorld();
        double dx = s.x + 0.5 - pos.getX(), dz = s.z + 0.5 - pos.getZ();
        if (dx * dx + dz * dz > 2.5 * 2.5) return;
        for (int dy = 0; dy <= 1; dy++) {
            Block b = w.getBlockAt(s.x, s.y + dy, s.z);
            BlockData data = b.getBlockData();
            if (!(data instanceof Openable) || !Builder.inZone(b)) continue; // за зоной не открыть
            String type = b.getType().name();
            if (type.startsWith("IRON_")) continue; // железные руками не открыть
            if (!type.endsWith("_DOOR") && !type.endsWith("_FENCE_GATE") && !(dy == 1 && type.endsWith("_TRAPDOOR"))) continue;
            Openable o = (Openable) data;
            if (o.isOpen()) continue;
            o.setOpen(true);
            b.setBlockData(o, true);
            p.swingMainHand();
            w.playSound(b.getLocation(), type.endsWith("_GATE") ? Sound.BLOCK_FENCE_GATE_OPEN
                : Sound.BLOCK_WOODEN_DOOR_OPEN, 1f, 1f);
            return;
        }
    }

    String debug() {
        String at = "";
        if (steps != null && idx < steps.size()) { PathStep c = steps.get(idx); at = "@" + c.x + "," + c.y + "," + c.z + ":" + c.move; }
        return (steps == null ? " nav=-" : (smart ? " nav=own" : " nav=mob") + " " + idx + "/" + steps.size() + at)
            + (search != null ? " search=" + search.expanded() : "");
    }

    private static double sq(double v) { return v * v; }
}
