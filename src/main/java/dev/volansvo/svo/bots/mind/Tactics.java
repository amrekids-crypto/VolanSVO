package dev.volansvo.svo.bots.mind;

import java.util.ArrayList;
import java.util.List;

/**
 * Где встать в бою. Набираем два десятка точек вокруг себя и вокруг врага, отбрасываем
 * негодные и оцениваем остальные: видно ли врага, прикрыты ли мы от остальных, та ли
 * дистанция, далеко ли бежать, не толпимся ли со своими, есть ли куда отойти.
 * Запрос считается по частям в несколько тиков: лучи дорогие.
 */
public final class Tactics {

    /** Что запросу нужно знать о мире. */
    public interface Probe {
        boolean standable(int x, int y, int z);

        /** Блок, за которым можно спрятаться (полный, не проходимый). */
        boolean solid(int x, int y, int z);

        boolean sees(double ex, double ey, double ez, double x, double y, double z);

        /** Внутри зоны с запасом. */
        boolean inZone(double x, double z);

        /** Жар карты угроз в точке. */
        double heat(double x, double z);
    }

    /** Чего бот хочет от позиции. */
    public static final class Need {
        /** Любимая дистанция до врага и допуск. */
        public double range = 14, rangeWidth = 8;
        /** Нужна линия огня (стрелку да, рукопашнику нет). */
        public boolean needFire = true;
        /** Веса оценок. */
        public double wFire = 1.0, wCover = 1.0, wHidden = 0.8, wHeat = 0.5, wRange = 0.9, wPath = 0.7, wSpace = 0.6, wRetreat = 0.4, wFlank = 0;
        /** Нужна точка, откуда врага НЕ видно (лечение, перезарядка). */
        public boolean hide;
    }

    public static final class Spot {
        public double x, y, z;
        /** С этой стороны от точки есть стена от врага: 2 - в полный рост, 1 - по пояс, 0 - нет. */
        public int cover;
        /** Куда шагнуть из-за стены, чтобы выстрелить (если cover > 0 и врага с точки не видно). */
        public double peekX, peekZ;
        public boolean hasPeek;
        public boolean fire;
        public double score;
        /** Оценки по отдельности: для журнала и отладки. */
        public String why;
    }

    private final List<Spot> open = new ArrayList<Spot>();
    private Spot best;
    private int next;
    private Need need;
    private double mx, my, mz, ex, ey, ez;
    private double[] others, allies;
    private boolean done = true;

    public boolean running() { return !done; }

    public Spot result() { return done ? best : null; }

    public List<Spot> candidates() { return open; }

    /**
     * Начать запрос. me - ноги бота, enemy - ноги врага (где он, по мнению бота),
     * others - глаза других известных врагов (x,y,z подряд), allies - свои (x,z подряд),
     * extra - готовые точки (высота и т.п., x,y,z подряд).
     */
    public void start(Need need, double mx, double my, double mz, double ex, double ey, double ez,
                      double[] others, double[] allies, double[] extra, Probe probe) {
        this.need = need;
        this.mx = mx; this.my = my; this.mz = mz;
        this.ex = ex; this.ey = ey; this.ez = ez;
        this.others = others == null ? new double[0] : others;
        this.allies = allies == null ? new double[0] : allies;
        open.clear();
        best = null;
        next = 0;
        done = false;
        int fy = (int) Math.floor(my + 0.01);
        double bearing = Math.atan2(mz - ez, mx - ex);
        // Кольцо вокруг врага на любимой дистанции: от текущего угла до 70 градусов вбок.
        for (int i = -3; i <= 3; i++) {
            double a = bearing + Math.toRadians(i * 23.0);
            add(ex + Math.cos(a) * need.range, ez + Math.sin(a) * need.range, fy, probe);
        }
        // Кольцо вокруг себя: короткий отскок.
        for (int i = 0; i < 8; i++) {
            double a = bearing + Math.PI / 8 + i * Math.PI / 4;
            double r = (i & 1) == 0 ? 3.5 : 7;
            add(mx + Math.cos(a) * r, mz + Math.sin(a) * r, fy, probe);
        }
        // Где стоим сейчас - тоже вариант.
        add(mx, mz, fy, probe);
        if (extra != null) {
            for (int i = 0; i + 2 < extra.length; i += 3) add(extra[i], extra[i + 2], (int) Math.floor(extra[i + 1] + 0.01), probe);
        }
        if (open.isEmpty()) done = true;
    }

    private void add(double x, double z, int fy, Probe probe) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        if (!probe.inZone(x, z)) return;
        int y = Integer.MIN_VALUE;
        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
            if (probe.standable(bx, fy + dy, bz)) { y = fy + dy; break; }
        }
        if (y == Integer.MIN_VALUE) return;
        for (Spot o : open) if (Math.floor(o.x) == bx && Math.floor(o.z) == bz && (int) o.y == y) return;
        Spot s = new Spot();
        s.x = bx + 0.5; s.y = y; s.z = bz + 0.5;
        open.add(s);
    }

    /** Оценить ещё до count точек. true - запрос закончен, result() готов. */
    public boolean step(int count, Probe probe) {
        if (done) return true;
        for (int i = 0; i < count && next < open.size(); i++) eval(open.get(next++), probe);
        if (next >= open.size()) done = true;
        return done;
    }

    private void eval(Spot s, Probe probe) {
        double dxe = ex - s.x, dze = ez - s.z;
        double de = Math.max(1e-6, Math.hypot(dxe, dze));
        double ux = dxe / de, uz = dze / de;
        int bx = (int) Math.floor(s.x), by = (int) s.y, bz = (int) Math.floor(s.z);

        // Стена со стороны врага: клетка на шаг к нему.
        int cx = bx + (int) Math.round(ux), cz = bz + (int) Math.round(uz);
        if (cx != bx || cz != bz) {
            boolean low = probe.solid(cx, by, cz), high = probe.solid(cx, by + 1, cz);
            s.cover = low && high ? 2 : low ? 1 : 0;
        }
        s.fire = probe.sees(s.x, s.y + 1.62, s.z, ex, ey + 1.5, ez);
        if (s.cover > 0 && !s.fire) {
            // Шаг вбок из-за стены, откуда врага видно.
            for (int side = -1; side <= 1 && !s.hasPeek; side += 2) {
                int px = bx + (int) Math.round(-uz * side), pz = bz + (int) Math.round(ux * side);
                if ((px == bx && pz == bz) || !probe.standable(px, by, pz)) continue;
                if (probe.sees(px + 0.5, by + 1.62, pz + 0.5, ex, ey + 1.5, ez)) {
                    s.hasPeek = true;
                    s.peekX = px + 0.5; s.peekZ = pz + 0.5;
                }
            }
        }
        boolean canFire = s.fire || s.hasPeek;
        if (need.hide ? s.fire : (need.needFire && !canFire)) { s.score = -1; s.why = need.hide ? "на виду" : "нет линии огня"; return; }

        // Дойти по прямой: каждая клетка на пути должна держать, перепад не больше блока.
        double dm = Math.hypot(s.x - mx, s.z - mz);
        if (dm > 1.2 && !straight(s, probe)) { s.score = -1; s.why = "не дойти"; return; }

        double fFire = s.fire ? 1 : s.hasPeek ? 0.85 : 0;
        double fCover = s.cover == 2 ? 1 : s.cover == 1 ? 0.6 : 0;
        if (need.hide) { fFire = 0; fCover = Math.max(fCover, 0.5); }
        double fHidden = 1;
        for (int i = 0; i + 2 < others.length; i += 3) {
            if (probe.sees(others[i], others[i + 1], others[i + 2], s.x, s.y + 1.0, s.z)) fHidden -= 0.5;
        }
        fHidden = Math.max(0, fHidden);
        double fHeat = 1.0 / (1.0 + probe.heat(s.x, s.z) * 0.35);
        double fRange = Utility.bell(de, need.range, need.rangeWidth);
        double fPath = Utility.inverse(dm, 1, 16);
        double fSpace = 1;
        for (int i = 0; i + 1 < allies.length; i += 2) {
            double da = Math.hypot(allies[i] - s.x, allies[i + 1] - s.z);
            if (da < 4) fSpace = Math.min(fSpace, da / 4.0);
        }
        // Куда отойти: клетка за спиной (от врага).
        int rx = bx - (int) Math.round(ux), rz = bz - (int) Math.round(uz);
        double fRetreat = probe.standable(rx, by, rz) || probe.standable(rx, by + 1, rz) || probe.standable(rx, by - 1, rz) ? 1 : 0;
        // Обход: насколько точка сбоку от линии «я - враг».
        double fFlank = 0;
        if (need.wFlank > 0) {
            double mxe = mx - ex, mze = mz - ez, ml = Math.max(1e-6, Math.hypot(mxe, mze));
            double cos = (-ux * mxe + -uz * mze) / ml;
            fFlank = Utility.clamp((1 - cos) / 0.66);
        }
        double sum = need.wFire * fFire + need.wCover * fCover + need.wHidden * fHidden + need.wHeat * fHeat
            + need.wRange * fRange + need.wPath * fPath + need.wSpace * fSpace + need.wRetreat * fRetreat + need.wFlank * fFlank;
        double total = need.wFire + need.wCover + need.wHidden + need.wHeat + need.wRange + need.wPath + need.wSpace + need.wRetreat + need.wFlank;
        s.score = total <= 0 ? 0 : sum / total;
        s.why = String.format(java.util.Locale.ROOT, "огонь%.1f укр%.1f скрыт%.1f жар%.1f дист%.1f путь%.1f", fFire, fCover, fHidden, fHeat, fRange, fPath);
        if (best == null || s.score > best.score) best = s;
    }

    private boolean straight(Spot s, Probe probe) {
        double dx = s.x - mx, dz = s.z - mz;
        double len = Math.hypot(dx, dz);
        int steps = (int) Math.ceil(len);
        int y = (int) Math.floor(my + 0.01);
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            int x = (int) Math.floor(mx + dx * t), z = (int) Math.floor(mz + dz * t);
            if (probe.standable(x, y, z)) continue;
            if (probe.standable(x, y + 1, z)) { y++; continue; }
            if (probe.standable(x, y - 1, z)) { y--; continue; }
            return false;
        }
        return Math.abs(y - (int) s.y) <= 1;
    }

    /** Стоит ли менять позицию: новая заметно лучше той, на которой стоим или к которой идём. */
    public static boolean worthMoving(Spot now, Spot candidate) {
        if (candidate == null || candidate.score <= 0) return false;
        if (now == null || now.score <= 0) return true;
        return candidate.score > now.score * 1.2;
    }
}
