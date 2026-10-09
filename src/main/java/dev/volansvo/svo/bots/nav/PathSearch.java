package dev.volansvo.svo.bots.nav;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Поиск пути A* по клеткам, где бот может стоять. Цена шага - время в тиках, поэтому
 * обход, прыжок через яму, подкоп и мостик сравниваются между собой честно.
 *
 * Поиск идёт порциями: {@link #step(long)} работает до заданного момента и возвращает
 * RUNNING, пока не закончил. Если до цели не дойти, отдаёт путь до ближайшей к ней клетки.
 */
public final class PathSearch {

    public enum State { RUNNING, FOUND, PARTIAL, FAILED }

    public static final class Options {
        /** Ломать мешающие блоки. */
        public boolean dig = true;
        /** Сколько блоков можно поставить (мостики, столбы). */
        public int blocks = 0;
        /** Наибольшая яма для прыжка: 0 - не прыгать, 2 - только с разбега. */
        public int parkourGap = 2;
        /** С какой высоты можно спрыгнуть на землю. */
        public int maxFall = 3;
        public int maxNodes = 3500;
        /** Дальше стольких блоков от старта не ищем: дошли до края - отдаём путь до него. */
        public int radius = 64;
        /** Множитель оценки: больше - быстрее поиск, но слабее различает удобный путь и неудобный. */
        public double weight = 1.03;
    }

    static final double LAND = 3.8;          // блок бегом
    static final double WATER = 9.1;         // блок вплавь
    static final double DIAG = 1.4142;
    static final double JUMP = 2.2;
    static final double DOOR = 4;
    static final double PARKOUR_EXTRA = 4;
    static final double PLACE = 12;
    static final double PILLAR = 20;
    static final double BREAK_EXTRA = 4;     // достать инструмент, навестись
    static final double NEAR_DANGER = 10;
    static final double ROUGH = 5;           // идти по листве
    static final double ROAD = -0.4;         // идти по дорожке
    static final double WALL = 0.5;          // впритирку к стене
    static final double LEDGE = 1.2;         // по краю обрыва
    static final double TURN = 0.35;         // смена направления
    static final double LADDER_UP = 8.5, LADDER_DOWN = 6.7;
    /** Присев идут втрое медленнее. */
    static final double CROUCH = 3.3;
    static final double SWIM_UP = 9, SWIM_DOWN = 5;
    private static final double[] FALL = fallTable(24);

    private static final int[] DX = {1, -1, 0, 0}, DZ = {0, 0, 1, -1};
    private static final int[] QX = {1, 1, -1, -1}, QZ = {1, -1, 1, -1};

    private static final class Node {
        final int x, y, z;
        double g = Double.MAX_VALUE, h, f;
        /** Удобство клетки: считается один раз. */
        double comfort;
        Node parent;
        byte move;
        long[] breaks;
        long place = Pos.NONE;
        int placed;
        int heap = -1;
        boolean closed;

        Node(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private final BlockView view;
    private final CostField field;
    private final NavGoal goal;
    private final Options opt;
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<Node>();
    private Node[] heap = new Node[256];
    private int heapSize;
    private final Node start;
    private Node best, end;
    private int expanded;
    private State state = State.RUNNING;

    public PathSearch(BlockView view, CostField field, NavGoal goal, Options opt, int sx, int sy, int sz) {
        this.view = view;
        this.field = field == null ? CostField.NONE : field;
        this.goal = goal;
        this.opt = opt;
        start = new Node(sx, sy, sz);
        start.g = 0;
        start.h = goal.heuristic(sx, sy, sz);
        start.f = start.h * opt.weight;
        nodes.put(Pos.pack(sx, sy, sz), start);
        push(start);
        best = start;
    }

    public State state() { return state; }
    public int expanded() { return expanded; }

    /** Работает, пока System.nanoTime() меньше deadline. */
    public State step(long deadline) {
        if (state != State.RUNNING) return state;
        int n = 0;
        while (heapSize > 0) {
            if ((++n & 15) == 0 && System.nanoTime() >= deadline) return State.RUNNING;
            Node cur = pop();
            cur.closed = true;
            expanded++;
            if (goal.reached(cur.x, cur.y, cur.z)) {
                end = cur;
                return state = State.FOUND;
            }
            if (cur.h < best.h) best = cur;
            // Цель дальше области поиска: первый узел на её краю уже лежит на хорошей дороге.
            // Но только если он ближе к цели, чем старт: иначе это край в обратную сторону
            // (впереди обрыв или вода), и бот развернулся бы назад.
            if (Math.abs(cur.x - start.x) >= opt.radius - 1 || Math.abs(cur.z - start.z) >= opt.radius - 1) {
                if (cur.h < start.h - LAND * 4) {
                    end = cur;
                    return state = State.PARTIAL;
                }
                continue;
            }
            if (expanded >= opt.maxNodes) break;
            expand(cur);
        }
        return finishNow();
    }

    /** Закончить сейчас: путь до ближайшей к цели из просмотренных клеток. */
    public State finishNow() {
        if (state != State.RUNNING) return state;
        if (best == start) return state = State.FAILED;
        end = best;
        return state = State.PARTIAL;
    }

    /** Путь от стартовой клетки (включительно). Пуст, если пути нет. */
    public List<PathStep> path() {
        List<PathStep> out = new ArrayList<PathStep>();
        for (Node n = end; n != null; n = n.parent) out.add(new PathStep(n.x, n.y, n.z, n.move, n.breaks, n.place));
        Collections.reverse(out);
        return out;
    }

    // ------------------------------------------------------------------ соседи

    private byte t(int x, int y, int z) { return view.type(x, y, z); }

    private boolean stand(int x, int y, int z) { return Cell.stand(view, x, y, z); }

    private void expand(Node n) {
        int x = n.x, y = n.y, z = n.z;
        byte feet = t(x, y, z);
        int fk = feet & Cell.KIND;
        // Над головой верхняя плита: стоим присев, отсюда только шаг в сторону или вниз.
        boolean low = Cell.lowHead(view, x, y, z);
        boolean headroom = !low && Cell.bodyFree(t(x, y + 2, z));
        // Под ногами опора: настоящая или только что поставленный блок.
        boolean firm = Cell.floor(t(x, y - 1, z)) || n.move == PathStep.PILLAR || n.move == PathStep.BRIDGE;

        for (int i = 0; i < 4; i++) cardinal(n, DX[i], DZ[i], fk, headroom, firm, low);
        if (low) return;

        for (int i = 0; i < 4; i++) {
            int nx = x + QX[i], nz = z + QZ[i];
            if (!stand(nx, y, nz) || (t(nx, y, nz) & Cell.KIND) == Cell.DOOR) continue;
            // Оба угла свободны, иначе цепляемся плечом.
            if (!Cell.feetFree(t(nx, y, z)) || !Cell.bodyFree(t(nx, y + 1, z))) continue;
            if (!Cell.feetFree(t(x, y, nz)) || !Cell.bodyFree(t(x, y + 1, nz))) continue;
            relax(n, nx, y, nz, enter(nx, y, nz) * DIAG, PathStep.DIAGONAL, null, Pos.NONE);
        }

        byte up = t(x, y + 1, z), down = t(x, y - 1, z);
        if (fk == Cell.CLIMB) {
            // Вверх по лестнице, и в люк над ней (откроем).
            if (((up & Cell.KIND) == Cell.CLIMB || (up & Cell.KIND) == Cell.DOOR) && headroom) relax(n, x, y + 1, z, LADDER_UP, PathStep.CLIMB, null, Pos.NONE);
            if ((down & Cell.KIND) == Cell.CLIMB || stand(x, y - 1, z)) relax(n, x, y - 1, z, LADDER_DOWN, PathStep.CLIMB, null, Pos.NONE);
        } else if ((down & Cell.KIND) == Cell.CLIMB) {
            relax(n, x, y - 1, z, LADDER_DOWN, PathStep.CLIMB, null, Pos.NONE);
        }
        if (fk == Cell.WATER) {
            if ((up & Cell.KIND) == Cell.WATER && headroom) relax(n, x, y + 1, z, SWIM_UP, PathStep.SWIM, null, Pos.NONE);
            if ((down & Cell.KIND) == Cell.WATER) relax(n, x, y - 1, z, SWIM_DOWN, PathStep.SWIM, null, Pos.NONE);
        }
        if (fk == Cell.AIR && firm) {
            if (n.placed < opt.blocks && view.canPlace(x, y, z)) {
                if (headroom) {
                    relax(n, x, y + 1, z, PILLAR, PathStep.PILLAR, null, Pos.pack(x, y, z));
                } else if (opt.dig) {
                    int ticks = breakable(x, y + 2, z, false);
                    if (ticks >= 0) relax(n, x, y + 1, z, PILLAR + ticks + BREAK_EXTRA, PathStep.PILLAR,
                        new long[]{Pos.pack(x, y + 2, z)}, Pos.pack(x, y, z));
                }
            }
            // Вниз копаем, только если под блоком есть следующий: в пустоту не проваливаемся.
            if (opt.dig && (down & Cell.KIND) == Cell.SOLID && (t(x, y - 2, z) & Cell.KIND) == Cell.SOLID) {
                int ticks = breakable(x, y - 1, z, true);
                if (ticks >= 0) relax(n, x, y - 1, z, ticks + BREAK_EXTRA + FALL[1], PathStep.DIG_DOWN,
                    new long[]{Pos.pack(x, y - 1, z)}, Pos.NONE);
            }
        }
    }

    private void cardinal(Node n, int dx, int dz, int fk, boolean headroom, boolean firm, boolean low) {
        int x = n.x, y = n.y, z = n.z, nx = x + dx, nz = z + dz;

        if (stand(nx, y, nz)) {
            relax(n, nx, y, nz, enter(nx, y, nz), PathStep.WALK, null, Pos.NONE);
        } else {
            byte f = t(nx, y, nz);
            // Низкий проход: присев (или сломать плиту, если это быстрее).
            if (Cell.crouch(view, nx, y, nz)) relax(n, nx, y, nz, LAND * CROUCH, PathStep.CROUCH, null, Pos.NONE);
            if (Cell.bodyFree(f) && Cell.bodyFree(t(nx, y + 1, nz))) {
                // Впереди пустота: спрыгнуть, перепрыгнуть или застелить.
                fall(n, nx, nz);
                if (opt.parkourGap > 0 && headroom && firm && (fk == Cell.AIR || fk == Cell.THIN)) parkour(n, dx, dz);
                int fkN = f & Cell.KIND;
                int below = t(nx, y - 1, nz) & Cell.KIND;
                if (n.placed < opt.blocks && firm && fkN == Cell.AIR && (below == Cell.AIR || below == Cell.WATER)
                        && view.canPlace(nx, y - 1, nz)) {
                    relax(n, nx, y, nz, LAND + PLACE, PathStep.BRIDGE, null, Pos.pack(nx, y - 1, nz));
                }
            } else if (opt.dig) {
                dig(n, nx, y, nz, PathStep.WALK, LAND, false);
            }
        }
        if (low) return; // присев не запрыгнуть

        if (stand(nx, y + 1, nz)) {
            if (headroom) {
                relax(n, nx, y + 1, nz, enter(nx, y + 1, nz) + JUMP, PathStep.ASCEND, null, Pos.NONE);
            } else if (opt.dig) {
                int ticks = breakable(x, y + 2, z, false);
                if (ticks >= 0) relax(n, nx, y + 1, nz, enter(nx, y + 1, nz) + JUMP + ticks + BREAK_EXTRA, PathStep.ASCEND,
                    new long[]{Pos.pack(x, y + 2, z)}, Pos.NONE);
            }
        } else if (opt.dig) {
            dig(n, nx, y + 1, nz, PathStep.ASCEND, LAND + JUMP, !headroom);
        }
    }

    /** Шаг с уступа: падаем до первой клетки, где можно стоять. */
    private void fall(Node n, int nx, int nz) {
        for (int k = 1; k <= 20; k++) {
            int yy = n.y - k;
            if (stand(nx, yy, nz)) {
                boolean water = (t(nx, yy, nz) & Cell.KIND) == Cell.WATER;
                if (k <= opt.maxFall || water) {
                    relax(n, nx, yy, nz, enter(nx, yy, nz) + FALL[k] + (k >= 3 && !water ? 4 : 0), PathStep.DESCEND, null, Pos.NONE);
                }
                return;
            }
            if (!Cell.bodyFree(t(nx, yy, nz))) return;
        }
    }

    /** Прыжок через яму в 1-2 клетки на тот же уровень. */
    private void parkour(Node n, int dx, int dz) {
        int x = n.x, y = n.y, z = n.z;
        for (int g = 1; g <= opt.parkourGap; g++) {
            int gx = x + dx * g, gz = z + dz * g;
            if (!Cell.bodyFree(t(gx, y, gz)) || !Cell.bodyFree(t(gx, y + 1, gz)) || !Cell.bodyFree(t(gx, y + 2, gz))) return;
            if (stand(gx, y, gz)) return;
            int lx = gx + dx, lz = gz + dz;
            if (!stand(lx, y, lz)) continue;
            if (!Cell.bodyFree(t(lx, y + 2, lz))) return;
            // На две клетки - только с разбега: позади должна быть клетка, куда отступить.
            if (g >= 2 && !stand(x - dx, y, z - dz)) return;
            relax(n, lx, y, lz, (g + 1) * LAND + PARKOUR_EXTRA * g + pitRisk(x + dx, y, z + dz), PathStep.PARKOUR, null, Pos.NONE);
            return;
        }
    }

    /** Чем грозит промах: глубокая яма или лава на дне делают прыжок дороже. */
    private double pitRisk(int x, int y, int z) {
        for (int k = 1; k <= 6; k++) {
            byte c = t(x, y - k, z);
            int kind = c & Cell.KIND;
            if (kind == Cell.DANGER) return 60;
            if (kind == Cell.WATER) return 0;
            if (!Cell.bodyFree(c)) return k > 4 ? 15 : 0;
        }
        // Под ямой пропасть: промах - смерть. Такой прыжок только если обход совсем далёк.
        return 150;
    }

    /**
     * Проломиться в клетку (nx,ty,nz): ломаем то, что мешает голове и ногам, если под
     * клеткой есть пол. needHead - сначала убрать блок над собой (для прыжка).
     */
    private void dig(Node n, int nx, int ty, int nz, byte move, double base, boolean needHead) {
        if (!Cell.floor(t(nx, ty - 1, nz))) return;
        long[] br = new long[3];
        int cnt = 0;
        double cost = base;
        if (needHead) {
            int ticks = breakable(n.x, n.y + 2, n.z, false);
            if (ticks < 0) return;
            br[cnt++] = Pos.pack(n.x, n.y + 2, n.z);
            cost += ticks + BREAK_EXTRA;
        }
        boolean headBroken = false;
        if (!Cell.bodyFree(t(nx, ty + 1, nz))) {
            int ticks = breakable(nx, ty + 1, nz, false);
            if (ticks < 0) return;
            br[cnt++] = Pos.pack(nx, ty + 1, nz);
            cost += ticks + BREAK_EXTRA;
            headBroken = true;
        }
        if (!Cell.feetFree(t(nx, ty, nz))) {
            int ticks = breakable(nx, ty, nz, headBroken);
            if (ticks < 0) return;
            br[cnt++] = Pos.pack(nx, ty, nz);
            cost += ticks + BREAK_EXTRA;
        } else if ((t(nx, ty, nz) & Cell.KIND) == Cell.HALF && !headBroken) {
            return; // плита под низким потолком: ломать нечего, а встать нельзя
        }
        if (cnt == 0 || (needHead && cnt == 1)) return;
        relax(n, nx, ty, nz, cost, move, Arrays.copyOf(br, cnt), Pos.NONE);
    }

    /**
     * Тики на слом блока или -1. Не трогаем блок, над которым песок/гравий (осыплется) и
     * рядом с которым лава или огонь. aboveCleared - блок над ним мы сами убираем.
     */
    private int breakable(int x, int y, int z, boolean aboveCleared) {
        int ticks = view.breakTicks(x, y, z);
        if (ticks < 0) return -1;
        byte above = t(x, y + 1, z);
        if (!aboveCleared && ((above & Cell.FALLS) != 0 || (above & Cell.KIND) == Cell.DANGER)) return -1;
        for (int i = 0; i < 4; i++) {
            if ((t(x + DX[i], y, z + DZ[i]) & Cell.KIND) == Cell.DANGER) return -1;
        }
        return ticks;
    }

    /** Цена входа в клетку пешком. */
    private double enter(int x, int y, int z) {
        byte f = t(x, y, z), h = t(x, y + 1, z);
        int fk = f & Cell.KIND, hk = h & Cell.KIND;
        double c = fk == Cell.WATER ? WATER : LAND;
        if (hk == Cell.WATER) c += 3;
        if (fk == Cell.DOOR || hk == Cell.DOOR) c += DOOR;
        return c;
    }

    /**
     * Насколько в клетке удобно: люди идут по дорожке и по середине прохода, а не по
     * листве, вдоль стены или по кромке обрыва. Опасное соседство (лава, огонь) - сюда же.
     */
    private double comfort(int x, int y, int z) {
        double c = 0;
        byte f = t(x, y, z);
        int fk = f & Cell.KIND;
        byte floor = fk == Cell.THIN || fk == Cell.HALF ? f : t(x, y - 1, z);
        if ((floor & Cell.ROUGH) != 0) c += ROUGH;
        else if ((floor & Cell.ROAD) != 0) c += ROAD;
        boolean danger = false, wall = false, ledge = false;
        for (int i = 0; i < 4; i++) {
            int nx = x + DX[i], nz = z + DZ[i];
            byte n = t(nx, y, nz);
            if ((n & Cell.KIND) == Cell.DANGER) danger = true;
            else if (!Cell.feetFree(n) || !Cell.bodyFree(t(nx, y + 1, nz))) wall = true;
            else if ((n & Cell.KIND) == Cell.AIR && Cell.bodyFree(t(nx, y - 1, nz))
                    && Cell.bodyFree(t(nx, y - 2, nz)) && Cell.bodyFree(t(nx, y - 3, nz))) ledge = true;
        }
        if (danger) c += NEAR_DANGER;
        if (wall) c += WALL;
        if (ledge) c += LEDGE;
        return c;
    }

    /** Смена направления на ровном месте чуть дороже: путь выходит прямее. */
    private static double turn(Node from, int x, int z, byte move) {
        if (from.parent == null || move > PathStep.DIAGONAL || from.move > PathStep.DIAGONAL) return 0;
        boolean same = Integer.signum(from.x - from.parent.x) == Integer.signum(x - from.x)
            && Integer.signum(from.z - from.parent.z) == Integer.signum(z - from.z);
        return same ? 0 : TURN;
    }

    private void relax(Node from, int x, int y, int z, double cost, byte move, long[] breaks, long place) {
        if (Math.abs(x - start.x) > opt.radius || Math.abs(z - start.z) > opt.radius) return;
        double extra = field.cost(x, y, z);
        if (extra == CostField.BLOCKED) return;
        long key = Pos.pack(x, y, z);
        Node n = nodes.get(key);
        if (n == null) {
            n = new Node(x, y, z);
            n.h = goal.heuristic(x, y, z);
            n.comfort = comfort(x, y, z);
            nodes.put(key, n);
        }
        if (n.closed) return;
        double g = from.g + Math.max(0.5, cost + extra + n.comfort + turn(from, x, z, move));
        if (g >= n.g) return;
        n.g = g;
        n.f = g + n.h * opt.weight;
        n.parent = from;
        n.move = move;
        n.breaks = breaks;
        n.place = place;
        n.placed = from.placed + (place != Pos.NONE ? 1 : 0);
        if (n.heap < 0) push(n); else up(n.heap);
    }

    // ------------------------------------------------------------------ очередь

    private void push(Node n) {
        if (heapSize == heap.length) heap = Arrays.copyOf(heap, heapSize * 2);
        heap[heapSize] = n;
        n.heap = heapSize;
        up(heapSize++);
    }

    private Node pop() {
        Node top = heap[0];
        Node last = heap[--heapSize];
        heap[heapSize] = null;
        if (heapSize > 0) { heap[0] = last; last.heap = 0; down(0); }
        top.heap = -1;
        return top;
    }

    private void up(int i) {
        Node n = heap[i];
        while (i > 0) {
            int p = (i - 1) >> 1;
            if (heap[p].f <= n.f) break;
            heap[i] = heap[p];
            heap[i].heap = i;
            i = p;
        }
        heap[i] = n;
        n.heap = i;
    }

    private void down(int i) {
        Node n = heap[i];
        for (;;) {
            int c = i * 2 + 1;
            if (c >= heapSize) break;
            if (c + 1 < heapSize && heap[c + 1].f < heap[c].f) c++;
            if (heap[c].f >= n.f) break;
            heap[i] = heap[c];
            heap[i].heap = i;
            i = c;
        }
        heap[i] = n;
        n.heap = i;
    }

    /** Сколько тиков падать n блоков (ванильная гравитация и сопротивление воздуха). */
    private static double[] fallTable(int max) {
        double[] t = new double[max + 1];
        double v = 0, d = 0;
        int tick = 0;
        for (int n = 1; n <= max; n++) {
            while (d < n) {
                v = (v + 0.08) * 0.98;
                d += v;
                tick++;
            }
            t[n] = tick;
        }
        return t;
    }
}
