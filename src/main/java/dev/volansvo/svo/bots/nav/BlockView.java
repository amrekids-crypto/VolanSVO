package dev.volansvo.svo.bots.nav;

/** Доступ поиска пути к миру. Реализация для сервера - в nms, для проверок - любая сетка. */
public interface BlockView {

    /** Класс клетки, см. {@link Cell}. Незагруженное и запретное - OBSTACLE. */
    byte type(int x, int y, int z);

    /** За сколько тиков бот сломает блок; -1 - не сломает или ломать нельзя. */
    int breakTicks(int x, int y, int z);

    /** Сюда можно поставить блок (за границей зоны нельзя). */
    default boolean canPlace(int x, int y, int z) { return true; }
}
