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
    /**
     * Верхняя плита, люк под потолком: занимает верх клетки. Стоять на ней можно, как на
     * полном блоке, а под ней (в клетке головы) проходят только присев.
     */
    public static final byte LOW = 9;

    public static final byte KIND = 0x0F;
    /** Ходить по такому неудобно и странно: листва. */
    public static final byte ROUGH = 0x10;
    /** Дорожка, брусчатка, доски: по такому ходят охотнее. */
    public static final byte ROAD = 0x20;
    public static final byte FALLS = 0x40;
    /** Препятствие в полный блок высотой (решётка, стеклянная панель, наковальня): сверху на нём стоят. */
    public static final byte TOP = (byte) 0x80;

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

    /** На этом можно стоять сверху. */
    public static boolean floor(byte t) {
        int k = t & KIND;
        return k == SOLID || k == LOW || k == OBSTACLE && (t & TOP) != 0;
    }

    /** Можно ли стоять ногами в клетке (x,y,z). */
    public static boolean stand(BlockView v, int x, int y, int z) {
        byte f = v.type(x, y, z);
        if (!feetFree(f) || !bodyFree(v.type(x, y + 1, z))) return false;
        int k = f & KIND;
        if (k == HALF) return bodyFree(v.type(x, y + 2, z));
        if (k == THIN || k == WATER || k == CLIMB) return true;
        return floor(v.type(x, y - 1, z));
    }

    /**
     * Пройти присев (проход в полтора блока): ноги в пустой клетке на полу, над ними верхняя
     * плита; или ноги на нижней плите, а потолок сразу над головой.
     */
    public static boolean crouch(BlockView v, int x, int y, int z) {
        byte f = v.type(x, y, z);
        int k = f & KIND;
        if (k == AIR) return (v.type(x, y + 1, z) & KIND) == LOW && floor(v.type(x, y - 1, z));
        return k == HALF && bodyFree(v.type(x, y + 1, z)) && !bodyFree(v.type(x, y + 2, z))
            && (v.type(x, y + 2, z) & KIND) != DANGER;
    }

    /** Стоим в клетке только присев (над головой плита или потолок над нижней плитой). */
    public static boolean lowHead(BlockView v, int x, int y, int z) {
        if ((v.type(x, y + 1, z) & KIND) == LOW) return true;
        return (v.type(x, y, z) & KIND) == HALF && !bodyFree(v.type(x, y + 2, z));
    }
}
