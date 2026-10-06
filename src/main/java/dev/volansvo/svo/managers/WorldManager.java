package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.maps.MapData;
import org.bukkit.*;
import org.bukkit.entity.*;

import java.io.File;
import java.util.*;

public class WorldManager {

    private final VolanSVO plugin;
    /** Дефолтные имена (когда активная карта не выбрана) - из config.yml. */
    private final String defaultLobby;
    private final String defaultGame;

    public WorldManager(VolanSVO plugin) {
        this.plugin = plugin;
        this.defaultLobby = plugin.getConfig().getString("worlds.lobby", "svo");
        this.defaultGame  = plugin.getConfig().getString("worlds.game",  "svogame_");
    }

    // ---------- имена миров (зависят от активной карты) ----------

    /** Имя source/лобби-мира карты (клонируется в игровой). */
    public String sourceWorldFor(MapData m) {
        return (m != null && m.getSourceWorld() != null) ? m.getSourceWorld() : defaultLobby;
    }
    /** Имя игрового мира (клона) карты: svo->svogame_, east->eastgame_. */
    public String gameWorldNameFor(MapData m) {
        return (m != null) ? m.getId() + "game_" : defaultGame;
    }
    private String lobbyWorldName() { return sourceWorldFor(plugin.getMapManager().getActiveMap()); }
    private String gameWorldName()  { return gameWorldNameFor(plugin.getMapManager().getActiveMap()); }

    /** Дефолтные (хаб svo) имена - для очереди/лобби, независимо от выбранной карты. */
    public String defaultGameWorldName() { return defaultGame; }
    public World  defaultGameWorld()     { return Bukkit.getWorld(defaultGame); }
    public World  defaultLobbyWorld()    { return Bukkit.getWorld(defaultLobby); }

    public World getLobbyWorld()    { return Bukkit.getWorld(lobbyWorldName()); }
    public World getGameWorld()     { return Bukkit.getWorld(gameWorldName()); }
    public String getGameWorldName(){ return gameWorldName(); }

    /**
     * Загружает мир если он существует на диске, иначе создаёт через mvclone.
     * Мир НЕ пересоздаётся если уже существует.
     */
    public void ensureGameWorld(final Runnable onReady) {
        final String gw = gameWorldName();
        if (Bukkit.getWorld(gw) != null) { onReady.run(); return; }

        File worldFolder = new File(Bukkit.getWorldContainer(), gw);
        if (worldFolder.exists()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvload " + gw);
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    if (Bukkit.getWorld(gw) != null) onReady.run();
                    else cloneWorld(onReady);
                }
            }, 60L);
        } else {
            cloneWorld(onReady);
        }
    }

    /** Публичный вызов клонирования лобби в игровой мир. */
    public void freshCloneWorld(final Runnable onReady) {
        cloneWorld(onReady);
    }

    /** Удаляет текущий игровой мир и создаёт его заново из лобби. */
    public void recreateGameWorld(final Runnable onReady) {
        final String gw = gameWorldName();
        World existing = Bukkit.getWorld(gw);
        if (existing != null) {
            World lobby = getLobbyWorld();
            Location fb = lobby != null ? lobby.getSpawnLocation() : null;
            for (Player p : existing.getPlayers()) if (fb != null) p.teleport(fb);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvdelete " + gw);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvconfirm");
        } else {
            deleteFolderNamed(gw);
        }
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { cloneWorld(onReady); }
        }, 120L);
    }

    private void cloneWorld(final Runnable onReady) {
        final String src = lobbyWorldName();
        final String dst = gameWorldName();
        cloneNamed(src, dst, onReady);
    }

    /**
     * Клонирует мир КОНКРЕТНОЙ карты (свежий: старый клон удаляется). Используется при
     * смене карты на старте раунда (напр. выбрали Восток - клонируем east -> eastgame_).
     */
    public void cloneMapWorld(final MapData map, final Runnable onReady) {
        final String src = sourceWorldFor(map);
        final String dst = gameWorldNameFor(map);
        // Удаляем старый клон, чтобы мир был свежий.
        World existing = Bukkit.getWorld(dst);
        if (existing != null) {
            World lobby = Bukkit.getWorld(src);
            Location fb = lobby != null ? lobby.getSpawnLocation() : null;
            for (Player p : existing.getPlayers()) if (fb != null) p.teleport(fb);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvdelete " + dst);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvconfirm");
        } else {
            deleteFolderNamed(dst);
        }
        // Ждём удаление, затем клонируем.
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() { cloneNamed(src, dst, onReady); }
        }, 60L);
    }

    /** Флашит источник на диск и клонирует src -> dst (с задержками против гонок mvclone). */
    private void cloneNamed(final String src, final String dst, final Runnable onReady) {
        // ВАЖНО: mvclone копирует region-файлы С ДИСКА. Если источник не сохранён -
        // чанки в клоне битые. Поэтому сначала флашим, и только следующим тиком клонируем.
        World lobby = Bukkit.getWorld(src);
        if (lobby != null) lobby.save();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvclone " + src + " " + dst);
                Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                    @Override public void run() {
                        World w = Bukkit.getWorld(dst);
                        if (w != null) {
                            w.setDifficulty(Difficulty.EASY);
                            w.setTime(6000);
                            w.setStorm(false);
                        }
                        onReady.run();
                    }
                }, 60L);
            }
        }, 40L);
    }

    /** Удаляет мир по ИМЕНИ (для смены карты: старый игровой мир svogame_/eastgame_). */
    public void deleteNamedWorld(String name) {
        if (name == null) return;
        World existing = Bukkit.getWorld(name);
        if (existing != null) {
            World lobby = getLobbyWorld();
            Location fb = lobby != null ? lobby.getSpawnLocation() : null;
            for (Player p : existing.getPlayers()) if (fb != null) p.teleport(fb);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvdelete " + name);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mvconfirm");
        } else {
            deleteFolderNamed(name);
        }
    }

    /**
     * Точка рестарта/лобби. Если у активной карты задана координата лобби - используем её.
     * Иначе ищем арморстенд svorestart (в targetWorld, затем в лобби).
     */
    public Location getRestartLocation(World targetWorld) {
        MapData m = plugin.getMapManager().getActiveMap();
        // Координату лобби карты применяем ТОЛЬКО к её собственным мирам (source/game),
        // иначе восточная точка ушла бы в мир svo при постановке в очередь.
        if (m != null && m.hasLobbySpawn()
                && (targetWorld.getName().equals(gameWorldNameFor(m))
                    || targetWorld.getName().equals(sourceWorldFor(m)))) {
            targetWorld.loadChunk(((int) m.getLobbyX()) >> 4, ((int) m.getLobbyZ()) >> 4, true);
            return new Location(targetWorld, m.getLobbyX(), m.getLobbyY(), m.getLobbyZ());
        }
        // 1. В targetWorld
        Location loc = findRestartStand(targetWorld);
        if (loc != null) {
            targetWorld.loadChunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4, true);
            return loc;
        }
        // 2. В лобби -> переносим координаты в targetWorld
        World lobby = getLobbyWorld();
        if (lobby != null && !lobby.equals(targetWorld)) {
            Location lobbyLoc = findRestartStand(lobby);
            if (lobbyLoc != null) {
                targetWorld.loadChunk(lobbyLoc.getBlockX() >> 4, lobbyLoc.getBlockZ() >> 4, true);
                return new Location(targetWorld,
                    lobbyLoc.getX(), lobbyLoc.getY(), lobbyLoc.getZ(),
                    lobbyLoc.getYaw(), lobbyLoc.getPitch());
            }
        }
        // 3. Спавн с проверкой Y
        Location spawn = targetWorld.getSpawnLocation();
        if (spawn.getY() < targetWorld.getMinHeight() + 5) spawn.setY(70);
        targetWorld.loadChunk(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4, true);
        return spawn;
    }

    private Location findRestartStand(World world) {
        for (Entity e : world.getEntitiesByClass(ArmorStand.class)) {
            if (e.getScoreboardTags().contains("svorestart")) return e.getLocation();
        }
        return null;
    }

    /** Удаляет игровой мир. Вызывается из /asvostop. */
    public void deleteGameWorld() {
        deleteNamedWorld(gameWorldName());
    }

    private void deleteFolderNamed(String name) {
        File worldFolder = new File(Bukkit.getWorldContainer(), name);
        if (worldFolder.exists()) deleteFolder(worldFolder);
    }

    private void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteFolder(f);
            else f.delete();
        }
        folder.delete();
    }
}
