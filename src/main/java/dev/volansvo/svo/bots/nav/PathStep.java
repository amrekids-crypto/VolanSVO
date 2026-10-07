package dev.volansvo.svo.bots.nav;

/** Узел пути: клетка, куда встать ногами, и чем в неё попадают. */
public final class PathStep {

    public static final byte WALK = 0;
    public static final byte DIAGONAL = 1;
    /** На блок вверх прыжком. */
    public static final byte ASCEND = 2;
    /** Шаг с уступа и падение. */
    public static final byte DESCEND = 3;
    /** Прыжок через яму. */
    public static final byte PARKOUR = 4;
    /** Блок под ноги впереди и шаг на него. */
    public static final byte BRIDGE = 5;
    /** Блок под себя в прыжке. */
    public static final byte PILLAR = 6;
    public static final byte CLIMB = 7;
    public static final byte SWIM = 8;
    /** Сломать блок под собой. */
    public static final byte DIG_DOWN = 9;

    public final int x, y, z;
    public final byte move;
    /** Блоки, которые надо сломать перед шагом (сверху вниз), или null. */
    public final long[] breaks;
    /** Куда поставить блок перед шагом, или {@link Pos#NONE}. */
    public final long place;

    public PathStep(int x, int y, int z, byte move, long[] breaks, long place) {
        this.x = x; this.y = y; this.z = z;
        this.move = move;
        this.breaks = breaks;
        this.place = place;
    }

    public PathStep(int x, int y, int z) {
        this(x, y, z, WALK, null, Pos.NONE);
    }

    /** Шаг меняет мир (ломает или ставит блоки). */
    public boolean works() {
        return breaks != null || place != Pos.NONE;
    }
}
