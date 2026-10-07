package dev.volansvo.svo.bots.nav;

/** Добавочная цена клетки: край зоны, логово босса, обстреливаемое место. */
public interface CostField {

    CostField NONE = (x, y, z) -> 0;

    /** Входить в клетку нельзя. */
    double BLOCKED = Double.MAX_VALUE;

    /** Сколько тиков накинуть за вход в клетку (меньше нуля - скидка) или {@link #BLOCKED}. */
    double cost(int x, int y, int z);
}
