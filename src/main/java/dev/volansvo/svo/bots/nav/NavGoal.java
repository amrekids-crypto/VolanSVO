package dev.volansvo.svo.bots.nav;

/** Куда ведёт путь: условие «дошёл» и оценка оставшейся цены в тиках. */
public interface NavGoal {

    boolean reached(int x, int y, int z);

    double heuristic(int x, int y, int z);

    /** Встать не дальше r блоков от точки (по высоте - в пределах пары блоков). */
    final class Near implements NavGoal {
        /** Цена блока пути по самой удобной дороге. */
        private static final double STEP = 3.4;
        private final int gx, gy, gz, r, dyMax;

        public Near(int x, int y, int z, int r) {
            this.gx = x; this.gy = y; this.gz = z;
            this.r = Math.max(1, r);
            this.dyMax = this.r >= 3 ? 2 : 1;
        }

        @Override
        public boolean reached(int x, int y, int z) {
            int dx = x - gx, dz = z - gz;
            return dx * dx + dz * dz <= r * r && Math.abs(y - gy) <= dyMax;
        }

        @Override
        public double heuristic(int x, int y, int z) {
            // По горизонтали - точная цена пути из прямых и диагональных шагов по хорошей
            // дороге: поиск не разбредается вширь и при этом различает удобное и неудобное.
            int ax = Math.abs(x - gx), az = Math.abs(z - gz);
            int lo = Math.min(ax, az), hi = Math.max(ax, az);
            double flat = Math.max(0, (hi - lo) * STEP + lo * STEP * 1.4142 - r * STEP);
            int dy = gy - y;
            if (dy > 0) return Math.max(flat + dy * 2.0, dy * 5.5);
            return flat - dy * 0.5;
        }
    }
}
