package dev.volansvo.svo.bots.nav;

/** Координаты блока в одном long: x и z по 26 бит, y - 12. */
public final class Pos {

    private Pos() {}

    public static final long NONE = Long.MIN_VALUE;

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int x(long p) { return (int) (p >> 38); }
    public static int z(long p) { return (int) (p << 26 >> 38); }
    public static int y(long p) { return (int) (p << 52 >> 52); }
}
