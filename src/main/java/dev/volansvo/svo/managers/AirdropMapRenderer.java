package dev.volansvo.svo.managers;

import org.bukkit.entity.Player;
import org.bukkit.map.*;

/**
 * Рисует курсор на карте в координатах места падения аирдропа.
 * Видно всем игрокам у которых есть карта.
 */
public class AirdropMapRenderer extends MapRenderer {

    private final double airX, airZ;

    public AirdropMapRenderer(double airX, double airZ) {
        super(false);
        this.airX = airX;
        this.airZ = airZ;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        MapCursorCollection cursors = canvas.getCursors();
        // Очищаем старые курсоры чтобы не накапливались
        while (cursors.size() > 0) {
            cursors.removeCursor(cursors.getCursor(0));
        }

        // Перевод мировых координат в координаты карты (-128..127)
        int scale = 1 << map.getScale().getValue();
        int cx = map.getCenterX();
        int cz = map.getCenterZ();
        int px = (int) Math.round((airX - cx) * 2.0 / scale);
        int pz = (int) Math.round((airZ - cz) * 2.0 / scale);
        if (px < -128) px = -128;
        if (px > 127)  px = 127;
        if (pz < -128) pz = -128;
        if (pz > 127)  pz = 127;

        MapCursor c = new MapCursor((byte) px, (byte) pz, (byte) 8, MapCursor.Type.RED_MARKER, true);
        cursors.addCursor(c);
    }
}
