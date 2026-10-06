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
 * Рисует на карте СВО ботов, у которых эта карта лежит в инвентаре (не выкинули),
 * той же белой стрелкой, что и игроков. Видно всем, у кого есть карта, в том же мире.
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
        MapCursorCollection cursors = canvas.getCursors();
        List<MapCursor> own = mine.get(viewer.getUniqueId());
        if (own == null) { own = new ArrayList<MapCursor>(); mine.put(viewer.getUniqueId(), own); }
        for (MapCursor c : own) cursors.removeCursor(c);
        own.clear();
        int scale = 1 << map.getScale().getValue();
        // Тиммейты (люди и боты) - зелёной стрелкой, даже если выкинули карту.
        for (Player o : viewer.getWorld().getPlayers()) {
            if (o.equals(viewer) || o.isDead() || o.getGameMode() == GameMode.SPECTATOR) continue;
            if (!mgr.sameTeamPublic(viewer.getUniqueId(), o.getUniqueId())) continue;
            MapCursor c = cursor(map, scale, o.getLocation(), MapCursor.Type.FRAME);
            if (c == null) continue;
            cursors.addCursor(c);
            own.add(c);
        }
        if (mgr.count() == 0) return;

        for (UUID id : mgr.botIds()) {
            Player b = org.bukkit.Bukkit.getPlayer(id);
            if (b == null || b.isDead() || b.getGameMode() == GameMode.SPECTATOR) continue;
            if (mgr.sameTeamPublic(viewer.getUniqueId(), id)) continue; // уже нарисован зелёным
            if (!b.getWorld().equals(viewer.getWorld()) || !carriesCached(b, map.getId())) continue;
            Location l = b.getLocation();
            int px = (int) Math.round((l.getX() - map.getCenterX()) * 2.0 / scale);
            int pz = (int) Math.round((l.getZ() - map.getCenterZ()) * 2.0 / scale);
            if (px < -128 || px > 127 || pz < -128 || pz > 127) continue;
            float yaw = l.getYaw();
            int dir = ((int) ((yaw + (yaw < 0 ? -8.0 : 8.0)) * 16.0 / 360.0)) & 15;
            MapCursor c = new MapCursor((byte) px, (byte) pz, (byte) dir, MapCursor.Type.PLAYER, true);
            cursors.addCursor(c);
            own.add(c);
        }
    }

    private static MapCursor cursor(MapView map, int scale, Location l, MapCursor.Type type) {
        int px = (int) Math.round((l.getX() - map.getCenterX()) * 2.0 / scale);
        int pz = (int) Math.round((l.getZ() - map.getCenterZ()) * 2.0 / scale);
        if (px < -128 || px > 127 || pz < -128 || pz > 127) return null;
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
