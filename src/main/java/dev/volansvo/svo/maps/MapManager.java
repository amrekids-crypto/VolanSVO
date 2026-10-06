package dev.volansvo.svo.maps;

import dev.volansvo.svo.VolanSVO;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class MapManager {

    private final VolanSVO plugin;
    private final File mapsDir;
    private final Map<String, MapData> maps = new LinkedHashMap<String, MapData>();

    /** ID карты на которой сейчас активна игра (null если нет игры). */
    private String activeMapId = null;

    public MapManager(VolanSVO plugin) {
        this.plugin = plugin;
        this.mapsDir = new File(plugin.getDataFolder(), "maps");
        if (!mapsDir.exists()) mapsDir.mkdirs();
        loadAll();
        // Если нет карт - попробовать миграцию старого svo
        if (maps.isEmpty()) migrateLegacySvo();
        // Карта "east" (Восток) - заводим если её ещё нет.
        registerEastIfMissing();
        // Текстовый файл сундуков на каждую карту (plugins/VolanSVO/chests/<id>.txt) -
        // если ещё нет, создаём из текущего списка карты, чтобы админ мог редактировать
        // координаты сам, без правки кода.
        for (MapData m : maps.values()) {
            ChestFileStore.ensureExists(plugin, m.getId(), m.getChestCoords());
        }
    }

    /** Регистрирует встроенную карту "east" (Восток), если её ещё нет в maps/. */
    private void registerEastIfMissing() {
        if (maps.containsKey("east")) return;
        MapData m = new MapData("east");
        m.setDisplayName("Восток");
        m.setSourceWorld("east");
        m.setWarden(448, 79, -544);
        m.setWardenName("Нетаньяху");
        m.setSpawnBox(-52, 948, 304, -1044, -44);       // Y=304 как в svo
        m.setBorder(448, -544, 1000);
        m.setMapBounds(-52, 948, -1044, -44);
        m.setMapId(107298);                              // filled_map карты east (миникарта + курсор аирдропа)
        m.setLobbySpawn(448, 311, -544);
        // Темплейты лута: X 435..440, Y 313, Z -554..-552 (18)
        for (int x = 435; x <= 440; x++)
            for (int z = -554; z <= -552; z++)
                m.addLootTemplate(x, 313, z);
        // Темплейты аирдропа: X 439..440, Y 313, Z -559..-556 (8)
        for (int x = 439; x <= 440; x++)
            for (int z = -559; z <= -556; z++)
                m.addAirdropTemplate(x, 313, z);
        for (int[] c : EastChests.COORDS) m.addChest(c[0], c[1], c[2]);
        register(m);
        plugin.getLogger().info("Registered map 'east' (Восток): " + m.getChestCoords().size()
            + " chests, " + m.getLootTemplates().size() + " loot templates, "
            + m.getAirdropTemplates().size() + " airdrop templates, warden=Нетаньяху");
    }

    private void loadAll() {
        File[] files = mapsDir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".yml")) continue;
            try {
                MapData m = MapData.loadFrom(f);
                maps.put(m.getId(), m);
                plugin.getLogger().info("Loaded map: " + m.getId() + " (display=" + m.getDisplayName() + ")");
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to load map " + f.getName() + ": " + t.getMessage());
            }
        }
    }

    /** Создаёт карту 'svo' из старого config.yml если миры svo/svogame_ существуют. */
    private void migrateLegacySvo() {
        if (plugin.getServer().getWorld("svo") == null) return;
        MapData m = new MapData("svo");
        m.setDisplayName("Донбасс");
        m.setSourceWorld("svo");
        // Берём warden из старого config.yml
        m.setWarden(
            plugin.getConfig().getDouble("warden.spawn_x", -565),
            plugin.getConfig().getDouble("warden.spawn_y", 102),
            plugin.getConfig().getDouble("warden.spawn_z", 479));
        m.setSpawnBox(
            plugin.getConfig().getInt("spawn_box.min_x", -1085),
            plugin.getConfig().getInt("spawn_box.max_x", -85),
            plugin.getConfig().getInt("spawn_box.min_y", 304),
            plugin.getConfig().getInt("spawn_box.min_z", -36),
            plugin.getConfig().getInt("spawn_box.max_z", 964));
        m.setBorder(
            plugin.getConfig().getDouble("worldborder.center_x", -585),
            plugin.getConfig().getDouble("worldborder.center_z", 464),
            plugin.getConfig().getDouble("worldborder.start_size", 1031));
        // Старая карта использовала filled_map id=106
        m.setMapBounds(-1085, -85, -36, 964);
        m.setMapId(106);
        // Темплейт-сундуки старой системы (lootCm 10 шт, airdrop 6 шт)
        int[][] lt = {{-587,313,446},{-586,313,446},{-586,313,447},{-587,313,447},{-588,313,447},
                      {-589,313,447},{-589,313,448},{-588,313,448},{-587,313,448},{-586,313,448}};
        for (int[] c : lt) m.addLootTemplate(c[0], c[1], c[2]);
        int[][] at = {{-586,313,444},{-587,313,444},{-586,313,443},{-587,313,443},{-586,313,442},{-587,313,442}};
        for (int[] c : at) m.addAirdropTemplate(c[0], c[1], c[2]);
        // 254 сундука лута (из place_chests.mcfunction)
        for (int[] c : LegacySvoChests.COORDS) m.addChest(c[0], c[1], c[2]);
        register(m);
        plugin.getLogger().info("Migrated legacy 'svo' (Донбасс) to maps/svo.yml: "
            + m.getChestCoords().size() + " chests, "
            + m.getLootTemplates().size() + " loot templates, "
            + m.getAirdropTemplates().size() + " airdrop templates");
    }

    public void register(MapData m) {
        maps.put(m.getId(), m);
        save(m);
    }

    public void unregister(String id) {
        maps.remove(id);
        File f = new File(mapsDir, id + ".yml");
        if (f.exists()) f.delete();
    }

    public void save(MapData m) {
        File f = new File(mapsDir, m.getId() + ".yml");
        try { m.saveTo(f); }
        catch (IOException e) {
            plugin.getLogger().warning("Failed to save map " + m.getId() + ": " + e.getMessage());
        }
    }

    public MapData get(String id) { return maps.get(id); }

    public Map<String, MapData> getAll() { return new LinkedHashMap<String, MapData>(maps); }

    public boolean exists(String id) { return maps.containsKey(id); }

    public String getActiveMapId() { return activeMapId; }
    public MapData getActiveMap() { return activeMapId != null ? maps.get(activeMapId) : null; }
    public void setActiveMap(String id) { this.activeMapId = id; }
    public void clearActiveMap() { this.activeMapId = null; }
}
