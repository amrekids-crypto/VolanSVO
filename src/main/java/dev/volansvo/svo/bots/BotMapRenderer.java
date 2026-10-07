package dev.volansvo.svo.bots;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Рисует на карте СВО всех игроков вместо ванильных стрелок: тебя синей, тиммейтов
 * зелёной, остальных (людей и ботов) с этой картой в инвентаре белой.
 */
public final class BotMapRenderer extends MapRenderer {

    private final BotManager mgr;
    /** Курсоры, которые мы добавили на холст каждого зрителя (холсты у зрителей разные). */
    private final java.util.Map<UUID, List<MapCursor>> mine = new java.util.HashMap<UUID, List<MapCursor>>();
    /** Несёт ли бот карту: сервер рисует карту каждый тик каждому, у кого она есть, а
     *  перебор инвентарей всех ботов на каждого зрителя после аирдропа заметно грузил тик. */
    private final java.util.Map<UUID, int[]> carryCache = new java.util.HashMap<UUID, int[]>();

    BotMapRenderer(BotManager mgr) {
        super(true); // свой набор курсоров на каждого зрителя (фильтр по миру)
        this.mgr = mgr;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player viewer) {
        if (mgr.isBot(viewer)) return; // боту карту не показывают, курсоры ему не нужны
        // Белые ванильные стрелки выключаем, только когда рисуем сами: иначе карта осталась бы без стрелок.
        if (map.isTrackingPosition()) hideVanillaPlayers(map);
        MapCursorCollection cursors = canvas.getCursors();
        List<MapCursor> own = mine.get(viewer.getUniqueId());
        if (own == null) { own = new ArrayList<MapCursor>(); mine.put(viewer.getUniqueId(), own); }
        for (MapCursor c : own) cursors.removeCursor(c);
        own.clear();
        int scale = 1 << map.getScale().getValue();
        // Тиммейты (люди и боты) - зелёной стрелкой, даже если выкинули карту; остальные
        // с этой картой в инвентаре - белой, как в ванилле.
        for (Player o : viewer.getWorld().getPlayers()) {
            if (o.equals(viewer) || o.isDead() || o.getGameMode() == GameMode.SPECTATOR || !viewer.canSee(o)) continue;
            MapCursor.Type type;
            if (mgr.sameTeamPublic(viewer.getUniqueId(), o.getUniqueId())) type = MapCursor.Type.FRAME;
            else if (carriesCached(o, map.getId())) type = MapCursor.Type.PLAYER;
            else continue;
            MapCursor c = cursor(map, scale, o.getLocation(), type, false);
            if (c == null) continue;
            cursors.addCursor(c);
            own.add(c);
        }
        // Ты сам - синей стрелкой поверх остальных (за краем карты - у края).
        MapCursor self = cursor(map, scale, viewer.getLocation(), MapCursor.Type.BLUE_MARKER, true);
        cursors.addCursor(self);
        own.add(self);
    }

    /**
     * Ванильные белые стрелки игроков на этой карте выключаем: всех (и тебя синим) рисует
     * этот рендерер. Стрелки тех, кто уже держит карту, сами не исчезнут, убираем их.
     */
    static void hideVanillaPlayers(MapView view) {
        if (!view.isTrackingPosition()) return;
        view.setTrackingPosition(false);
        try {
            java.lang.reflect.Field f = org.bukkit.craftbukkit.map.CraftMapView.class.getDeclaredField("worldMap");
            f.setAccessible(true);
            net.minecraft.world.level.saveddata.maps.MapItemSavedData data =
                (net.minecraft.world.level.saveddata.maps.MapItemSavedData) f.get(view);
            data.decorations.values().removeIf(d -> d.type().is(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.PLAYER)
                || d.type().is(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.PLAYER_OFF_MAP)
                || d.type().is(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.PLAYER_OFF_LIMITS));
            data.setDecorationsDirty();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    private static MapCursor cursor(MapView map, int scale, Location l, MapCursor.Type type, boolean clamp) {
        int px = (int) Math.round((l.getX() - map.getCenterX()) * 2.0 / scale);
        int pz = (int) Math.round((l.getZ() - map.getCenterZ()) * 2.0 / scale);
        if (px < -128 || px > 127 || pz < -128 || pz > 127) {
            if (!clamp) return null;
            px = Math.max(-128, Math.min(127, px));
            pz = Math.max(-128, Math.min(127, pz));
        }
        float yaw = l.getYaw();
        int dir = ((int) ((yaw + (yaw < 0 ? -8.0 : 8.0)) * 16.0 / 360.0)) & 15;
        return new MapCursor((byte) px, (byte) pz, (byte) dir, type, true);
    }

    private boolean carriesCached(Player p, int mapId) {
        int now = mgr.now();
        int[] c = carryCache.get(p.getUniqueId());
        if (c == null || c[1] != mapId || now - c[0] >= 10) {
            c = new int[]{now, mapId, carries(p, mapId) ? 1 : 0};
            carryCache.put(p.getUniqueId(), c);
        }
        return c[2] == 1;
    }

    private static boolean carries(Player p, int mapId) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType() != Material.FILLED_MAP || !(it.getItemMeta() instanceof MapMeta)) continue;
            MapMeta m = (MapMeta) it.getItemMeta();
            if (m.hasMapView() && m.getMapView() != null && m.getMapView().getId() == mapId) return true;
        }
        return false;
    }
}
