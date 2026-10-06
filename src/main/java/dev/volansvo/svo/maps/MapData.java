package dev.volansvo.svo.maps;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;

/**
 * Данные одной карты. Хранится в plugins/VolanSVO/maps/<id>.yml
 *  - id          : файлово-безопасное имя (donbass, svo, pripyat)
 *  - displayName : показываемое в чате
 *  - sourceWorld : имя мира-источника (клонируется при старте игры)
 *  - gameWorld   : имя клона (= id + "_game")
 *  - warden(X,Y,Z), spawnBox, worldBorder
 */
public class MapData {

    private final String id;
    private String displayName;
    private String sourceWorld;

    // warden
    private double wardenX, wardenY, wardenZ;
    private boolean wardenSet = false;
    private String wardenName = "Жириновский";   // имя босса (у east - "Нетаньяху")

    // точка лобби/рестарта (куда игроков телепортит в очереди/после игры)
    private double lobbyX, lobbyY, lobbyZ;
    private boolean lobbySet = false;

    // spawn_box
    private int spawnMinX, spawnMaxX, spawnY, spawnMinZ, spawnMaxZ;
    private boolean spawnBoxSet = false;

    // worldborder
    private double borderCenterX, borderCenterZ, borderSize;
    private boolean borderSet = false;

    // map (filled_map) bounds + map_id
    private int mapMinX, mapMaxX, mapMinZ, mapMaxZ;
    private int mapId = -1;
    private boolean mapBoundsSet = false;

    // chests
    private final java.util.List<int[]> chestCoords     = new java.util.ArrayList<int[]>();
    private final java.util.List<int[]> lootTemplates   = new java.util.ArrayList<int[]>();
    private final java.util.List<int[]> airdropTemplates= new java.util.ArrayList<int[]>();

    public MapData(String id) { this.id = id; }

    public String getId() { return id; }
    public String getDisplayName() { return displayName != null ? displayName : id; }
    public void setDisplayName(String s) { this.displayName = s; }

    public String getSourceWorld() { return sourceWorld; }
    public void setSourceWorld(String s) { this.sourceWorld = s; }

    public String getGameWorld() { return id + "_game"; }

    public boolean hasWarden() { return wardenSet; }
    public double getWardenX() { return wardenX; }
    public double getWardenY() { return wardenY; }
    public double getWardenZ() { return wardenZ; }
    public void setWarden(double x, double y, double z) {
        this.wardenX = x; this.wardenY = y; this.wardenZ = z; this.wardenSet = true;
    }
    public String getWardenName() { return wardenName != null ? wardenName : "Жириновский"; }
    public void setWardenName(String n) { if (n != null && !n.isEmpty()) this.wardenName = n; }

    public boolean hasLobbySpawn() { return lobbySet; }
    public double getLobbyX() { return lobbyX; }
    public double getLobbyY() { return lobbyY; }
    public double getLobbyZ() { return lobbyZ; }
    public void setLobbySpawn(double x, double y, double z) {
        this.lobbyX = x; this.lobbyY = y; this.lobbyZ = z; this.lobbySet = true;
    }

    public boolean hasSpawnBox() { return spawnBoxSet; }
    public int getSpawnMinX() { return spawnMinX; }
    public int getSpawnMaxX() { return spawnMaxX; }
    public int getSpawnY()    { return spawnY; }
    public int getSpawnMinZ() { return spawnMinZ; }
    public int getSpawnMaxZ() { return spawnMaxZ; }
    public void setSpawnBox(int minX, int maxX, int y, int minZ, int maxZ) {
        this.spawnMinX = minX; this.spawnMaxX = maxX; this.spawnY = y;
        this.spawnMinZ = minZ; this.spawnMaxZ = maxZ; this.spawnBoxSet = true;
    }

    public boolean hasBorder() { return borderSet; }
    public double getBorderCenterX() { return borderCenterX; }
    public double getBorderCenterZ() { return borderCenterZ; }
    public double getBorderSize()    { return borderSize; }
    public void setBorder(double cx, double cz, double size) {
        this.borderCenterX = cx; this.borderCenterZ = cz; this.borderSize = size; this.borderSet = true;
    }

    public boolean hasMapBounds() { return mapBoundsSet; }
    public int getMapMinX() { return mapMinX; }
    public int getMapMaxX() { return mapMaxX; }
    public int getMapMinZ() { return mapMinZ; }
    public int getMapMaxZ() { return mapMaxZ; }
    public int getMapId()   { return mapId; }
    public boolean hasMapId() { return mapId >= 0; }
    public void setMapBounds(int minX, int maxX, int minZ, int maxZ) {
        this.mapMinX = minX; this.mapMaxX = maxX;
        this.mapMinZ = minZ; this.mapMaxZ = maxZ;
        this.mapBoundsSet = true;
    }
    public void setMapId(int id) { this.mapId = id; }

    public java.util.List<int[]> getChestCoords()      { return chestCoords; }
    public java.util.List<int[]> getLootTemplates()    { return lootTemplates; }
    public java.util.List<int[]> getAirdropTemplates() { return airdropTemplates; }
    public void clearChests()             { chestCoords.clear(); }
    public void addChest(int x, int y, int z)              { chestCoords.add(new int[]{x, y, z}); }
    public void clearLootTemplates()      { lootTemplates.clear(); }
    public void addLootTemplate(int x, int y, int z)       { lootTemplates.add(new int[]{x, y, z}); }
    public void clearAirdropTemplates()   { airdropTemplates.clear(); }
    public void addAirdropTemplate(int x, int y, int z)    { airdropTemplates.add(new int[]{x, y, z}); }

    public boolean hasChests()           { return !chestCoords.isEmpty(); }
    public boolean hasLootTemplates()    { return lootTemplates.size() >= 1; }
    public boolean hasAirdropTemplates() { return airdropTemplates.size() >= 1; }

    public boolean isReady() {
        // Миникарта (mapBounds + mapId) опциональна - без неё игра идёт, просто нет карты-предмета.
        return sourceWorld != null && wardenSet && spawnBoxSet && borderSet
            && hasChests() && hasLootTemplates() && hasAirdropTemplates();
    }

    public void saveTo(File file) throws IOException {
        YamlConfiguration y = new YamlConfiguration();
        y.set("id",            id);
        y.set("displayName",   displayName);
        y.set("sourceWorld",   sourceWorld);
        if (wardenSet) {
            y.set("warden.x", wardenX);
            y.set("warden.y", wardenY);
            y.set("warden.z", wardenZ);
        }
        y.set("warden.name", wardenName);
        if (lobbySet) {
            y.set("lobby.x", lobbyX);
            y.set("lobby.y", lobbyY);
            y.set("lobby.z", lobbyZ);
        }
        if (spawnBoxSet) {
            y.set("spawn_box.min_x", spawnMinX);
            y.set("spawn_box.max_x", spawnMaxX);
            y.set("spawn_box.y",     spawnY);
            y.set("spawn_box.min_z", spawnMinZ);
            y.set("spawn_box.max_z", spawnMaxZ);
        }
        if (borderSet) {
            y.set("worldborder.center_x",  borderCenterX);
            y.set("worldborder.center_z",  borderCenterZ);
            y.set("worldborder.start_size", borderSize);
        }
        if (mapBoundsSet) {
            y.set("map.min_x", mapMinX);
            y.set("map.max_x", mapMaxX);
            y.set("map.min_z", mapMinZ);
            y.set("map.max_z", mapMaxZ);
        }
        if (hasMapId()) y.set("map.id", mapId);
        if (!chestCoords.isEmpty())      y.set("chests.list",     coordsToStringList(chestCoords));
        if (!lootTemplates.isEmpty())    y.set("chests.loot_templates",    coordsToStringList(lootTemplates));
        if (!airdropTemplates.isEmpty()) y.set("chests.airdrop_templates", coordsToStringList(airdropTemplates));
        y.save(file);
    }

    private static java.util.List<String> coordsToStringList(java.util.List<int[]> src) {
        java.util.List<String> out = new java.util.ArrayList<String>(src.size());
        for (int[] c : src) out.add(c[0] + " " + c[1] + " " + c[2]);
        return out;
    }

    private static java.util.List<int[]> stringListToCoords(java.util.List<String> in) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>(in.size());
        for (String s : in) {
            String[] parts = s.trim().split("\\s+");
            if (parts.length < 3) continue;
            try {
                out.add(new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
                });
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static MapData loadFrom(File file) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        String id = y.getString("id", file.getName().replace(".yml", ""));
        MapData m = new MapData(id);
        m.setDisplayName(y.getString("displayName", id));
        m.setSourceWorld(y.getString("sourceWorld", id));
        ConfigurationSection w = y.getConfigurationSection("warden");
        if (w != null && w.contains("x")) {
            m.setWarden(w.getDouble("x"), w.getDouble("y"), w.getDouble("z"));
        }
        if (w != null && w.contains("name")) m.setWardenName(w.getString("name"));
        ConfigurationSection lob = y.getConfigurationSection("lobby");
        if (lob != null && lob.contains("x")) {
            m.setLobbySpawn(lob.getDouble("x"), lob.getDouble("y"), lob.getDouble("z"));
        }
        ConfigurationSection sb = y.getConfigurationSection("spawn_box");
        if (sb != null && sb.contains("min_x")) {
            m.setSpawnBox(sb.getInt("min_x"), sb.getInt("max_x"),
                          sb.getInt("y"), sb.getInt("min_z"), sb.getInt("max_z"));
        }
        ConfigurationSection b = y.getConfigurationSection("worldborder");
        if (b != null && b.contains("center_x")) {
            m.setBorder(b.getDouble("center_x"), b.getDouble("center_z"), b.getDouble("start_size"));
        }
        ConfigurationSection mp = y.getConfigurationSection("map");
        if (mp != null) {
            if (mp.contains("min_x")) {
                m.setMapBounds(mp.getInt("min_x"), mp.getInt("max_x"),
                               mp.getInt("min_z"), mp.getInt("max_z"));
            }
            if (mp.contains("id")) m.setMapId(mp.getInt("id"));
        }
        ConfigurationSection ch = y.getConfigurationSection("chests");
        if (ch != null) {
            if (ch.contains("list"))                 m.chestCoords.addAll(stringListToCoords(ch.getStringList("list")));
            if (ch.contains("loot_templates"))       m.lootTemplates.addAll(stringListToCoords(ch.getStringList("loot_templates")));
            if (ch.contains("airdrop_templates"))    m.airdropTemplates.addAll(stringListToCoords(ch.getStringList("airdrop_templates")));
        }
        return m;
    }
}
