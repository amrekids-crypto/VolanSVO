package dev.volansvo.svo.bots.nav;

/**
 * Клетка мира глазами поиска пути. Младшие 4 бита - класс, остальные - признаки: {@link #FALLS} - блок
 * осыпается (песок, гравий), {@link #ROUGH} и {@link #ROAD} - удобство поверхности.
 */
public final class Cell {

    private Cell() {}

    /** Пусто: трава, цветы, факелы, таблички и всё без коллизии. */
    public static final byte AIR = 0;
    /** Полный блок: на нём стоят, сквозь него не пройти. */
    public static final byte SOLID = 1;
    public static final byte WATER = 2;
    /** Лава, огонь, магма, кактус: ни входить, ни стоять сверху. */
    public static final byte DANGER = 3;
    /** Деревянная дверь или калитка: проходима, её открывают. */
    public static final byte DOOR = 4;
    /** Лестница, лиана, строительные леса. */
    public static final byte CLIMB = 5;
    /** Ковёр, люк на полу, кувшинка: стоят прямо в этой клетке. */
    public static final byte THIN = 6;
    /** Нижняя плита, кровать: стоят в этой клетке на полблока выше. */
    public static final byte HALF = 7;
    /** Забор, стекло-панель, решётка: не пройти и не встать. */
    public static final byte OBSTACLE = 8;

    public static final byte KIND = 0x0F;
    /** Ходить по такому неудобно и странно: листва. */
    public static final byte ROUGH = 0x10;
    /** Дорожка, брусчатка, доски: по такому ходят охотнее. */
    public static final byte ROAD = 0x20;
    public static final byte FALLS = 0x40;

    public static int kind(byte t) { return t & KIND; }

    /** Тело (голова, корпус) может находиться в клетке. */
    public static boolean bodyFree(byte t) {
        int k = t & KIND;
        return k == AIR || k == WATER || k == DOOR || k == CLIMB;
    }

    /** Ноги могут находиться в клетке. */
    public static boolean feetFree(byte t) {
        int k = t & KIND;
        return k == AIR || k == WATER || k == DOOR || k == CLIMB || k == THIN || k == HALF;
    }

    /** Можно ли стоять ногами в клетке (x,y,z). */
    public static boolean stand(BlockView v, int x, int y, int z) {
        byte f = v.type(x, y, z);
        if (!feetFree(f) || !bodyFree(v.type(x, y + 1, z))) return false;
        int k = f & KIND;
        if (k == HALF) return bodyFree(v.type(x, y + 2, z));
        if (k == THIN || k == WATER || k == CLIMB) return true;
        return (v.type(x, y - 1, z) & KIND) == SOLID;
    }
}
