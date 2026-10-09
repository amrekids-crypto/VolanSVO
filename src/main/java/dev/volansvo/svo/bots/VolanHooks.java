package dev.volansvo.svo.bots;

import dev.volansvo.svo.GameState;
import dev.volansvo.svo.SvoPlayer;
import dev.volansvo.svo.VolanSVO;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Warden;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Всё, что бот узнаёт об игре СВО, идёт через этот класс. */
public final class VolanHooks {

    private final VolanSVO plugin;

    /** Кэш висящего в воздухе аирдропа (airpig), обновляется раз в 2 секунды. */
    private ArmorStand airpig;
    private int airpigScanTick = -100;

    public VolanHooks(VolanSVO plugin) {
        this.plugin = plugin;
    }

    public boolean gameActive() {
        return plugin.getGameManager().getState() == GameState.ACTIVE;
    }

    public World gameWorld() {
        return plugin.getWorldManager().getGameWorld();
    }

    public boolean inGame(UUID uid) {
        return plugin.getGameManager().isPlayerInGame(uid);
    }

    public boolean sameTeam(UUID a, UUID b) {
        return plugin.getGameManager().sameTeam(a, b);
    }

    public List<Player> alivePlayers() {
        return plugin.getGameManager().getActivePlayers();
    }

    public int teamIdOf(UUID uid) {
        SvoPlayer sp = plugin.getGameManager().getSvoPlayer(uid);
        return sp == null ? -1 : sp.getTeamId();
    }

    /** Где скоро взорвётся динамит хаоса. */
    public java.util.List<Location> pendingBlasts() {
        try {
            return plugin.getChaosManager() == null ? java.util.Collections.<Location>emptyList() : plugin.getChaosManager().pendingBlasts();
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /** Сколько тиков прошло с начала раунда. */
    public int elapsedTicks() {
        return plugin.getGameManager().getTimerTicks() - plugin.getGameManager().getCurrentTick();
    }

    public int remainingTicks() {
        return plugin.getGameManager().getCurrentTick();
    }

    /** Расписание сужений зоны (см. GameManager.zoneSchedule), пусто - нет игры. */
    public long[][] zoneSchedule() {
        try {
            return plugin.getGameManager().zoneSchedule();
        } catch (Throwable t) {
            return new long[0][];
        }
    }

    public boolean isNukeButton(ItemStack it) {
        return plugin.getGameManager().isNukeButton(it);
    }

    public boolean hasNukeButton(Player p) {
        return plugin.getGameManager().hasNukeButton(p);
    }

    public void launchNuke(Player p) {
        String[] targets = {"washington", "kyiv", "moscow"};
        plugin.getWardenManager().launchRocket(p, targets[(int) (Math.random() * targets.length)]);
    }

    public int[][] chests() {
        return plugin.getLootManager().getActiveChests();
    }

    public int lootGeneration() {
        return plugin.getLootManager().getLootGeneration();
    }

    public Location airdropChest() {
        return plugin.getAirdropManager().getLastLanded();
    }

    public ArmorStand airpig(int serverTick) {
        if (serverTick - airpigScanTick >= 40) {
            airpigScanTick = serverTick;
            airpig = null;
            World w = gameWorld();
            if (w != null) {
                for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                    if (e.getScoreboardTags().contains("airpig") && e.isValid()) { airpig = (ArmorStand) e; break; }
                }
            }
        }
        return (airpig != null && airpig.isValid()) ? airpig : null;
    }

    public Warden warden() {
        return plugin.getWardenManager().getWarden();
    }

    /** Сколько жизней осталось у участника (1 - последняя). */
    public int livesLeft(UUID uid) {
        SvoPlayer sp = plugin.getGameManager().getSvoPlayer(uid);
        return sp == null ? 1 : sp.getLives();
    }

    /** Имя текущей карты: по нему хранятся тропы игроков. */
    public String mapKey() {
        try {
            String id = plugin.getMapManager().getActiveMapId();
            return id == null ? "default" : id.replaceAll("[^A-Za-z0-9_-]", "_");
        } catch (Throwable t) {
            return "default";
        }
    }

    public Player player(UUID uid) {
        return Bukkit.getPlayer(uid);
    }
}
