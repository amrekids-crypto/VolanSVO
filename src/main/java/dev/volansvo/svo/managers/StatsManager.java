package dev.volansvo.svo.managers;

import dev.volansvo.svo.SvoPlayer;
import dev.volansvo.svo.VolanSVO;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StatsManager {

    private final VolanSVO plugin;
    private final Map<UUID, SvoPlayer> cache = new HashMap<UUID, SvoPlayer>();
    private File statsFile;
    private FileConfiguration statsConfig;

    public StatsManager(VolanSVO plugin) {
        this.plugin = plugin;
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        statsFile = new File(plugin.getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            try { statsFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
    }

    public SvoPlayer getOrCreate(Player player) {
        SvoPlayer sp = cache.get(player.getUniqueId());
        if (sp == null) {
            sp = new SvoPlayer(player);
            load(sp);
            cache.put(player.getUniqueId(), sp);
        }
        return sp;
    }

    private void load(SvoPlayer sp) {
        String path = sp.getUuid().toString();
        if (!statsConfig.contains(path)) return;
        sp.setTotalGames(statsConfig.getInt(path + ".games", 0));
        sp.setTotalWins(statsConfig.getInt(path + ".wins", 0));
        sp.setTotalKills(statsConfig.getInt(path + ".kills", 0));
    }

    public void save(SvoPlayer sp) {
        // Боты статистику не ведут.
        if (plugin.getBotManager() != null && plugin.getBotManager().isBot(sp.getUuid())) return;
        String path = sp.getUuid().toString();
        statsConfig.set(path + ".name",  sp.getName());
        statsConfig.set(path + ".games", sp.getTotalGames());
        statsConfig.set(path + ".wins",  sp.getTotalWins());
        statsConfig.set(path + ".kills", sp.getTotalKills());
        try { statsConfig.save(statsFile); } catch (IOException e) { e.printStackTrace(); }
    }

    public void saveAll() {
        for (SvoPlayer sp : cache.values()) save(sp);
    }

    public SvoPlayer get(UUID uid) { return cache.get(uid); }

    /** Забыть игрока из кэша (бот ушёл с сервера). */
    public void forget(UUID uid) { cache.remove(uid); }
}
