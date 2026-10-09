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
        put(sp);
        flushLater();
    }

    /** Все из кэша - одной записью файла (раньше файл перезаписывался на каждого игрока). */
    public void saveAll() {
        for (SvoPlayer sp : cache.values()) {
            if (plugin.getBotManager() != null && plugin.getBotManager().isBot(sp.getUuid())) continue;
            put(sp);
        }
        // Плагин выключается - пишем сразу (планировщик уже не работает), иначе - в фоне.
        if (plugin.isEnabled()) flushLater(); else flushNow();
    }

    private void put(SvoPlayer sp) {
        String path = sp.getUuid().toString();
        statsConfig.set(path + ".name",  sp.getName());
        statsConfig.set(path + ".games", sp.getTotalGames());
        statsConfig.set(path + ".wins",  sp.getTotalWins());
        statsConfig.set(path + ".kills", sp.getTotalKills());
    }

    private boolean flushQueued;

    /** Записать файл в конце тика: несколько убийств за тик - одна запись, и не в основном потоке. */
    private void flushLater() {
        if (flushQueued) return;
        flushQueued = true;
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                flushQueued = false;
                final String data = statsConfig.saveToString();
                final File f = statsFile;
                final long seq = ++snapshotSeq;
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> write(f, data, seq));
            });
        } catch (Throwable t) {
            // Плагин выключается - планировщик не принимает задачи, пишем сразу.
            flushQueued = false;
            flushNow();
        }
    }

    private void flushNow() {
        write(statsFile, statsConfig.saveToString(), ++snapshotSeq);
    }

    /** Номер снимка: запись в фоне, начатая раньше, не затрёт более свежий файл. */
    private long snapshotSeq;
    private static long writtenSeq;

    private static synchronized void write(File f, String data, long seq) {
        if (seq <= writtenSeq) return;
        writtenSeq = seq;
        try {
            java.nio.file.Files.write(f.toPath(), data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public SvoPlayer get(UUID uid) { return cache.get(uid); }

    /** Забыть игрока из кэша (бот ушёл с сервера). */
    public void forget(UUID uid) { cache.remove(uid); }
}
