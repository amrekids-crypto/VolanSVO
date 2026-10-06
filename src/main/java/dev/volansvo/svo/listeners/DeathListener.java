package dev.volansvo.svo.listeners;

import dev.volansvo.svo.GameState;
import dev.volansvo.svo.SvoPlayer;
import dev.volansvo.svo.VolanSVO;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;

public class DeathListener implements Listener {

    private final VolanSVO plugin;

    public DeathListener(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (plugin.getGameManager().getState() != GameState.ACTIVE) return;
        if (!plugin.getGameManager().isPlayerInGame(player.getUniqueId())) return;

        Player killer = player.getKiller();
        if (killer != null && plugin.getGameManager().isPlayerInGame(killer.getUniqueId())) {
            SvoPlayer ksp = plugin.getGameManager().getSvoPlayer(killer.getUniqueId());
            // Убийства ботами, убийства ботов и игры один-на-ботов в постоянную статистику не идут.
            boolean counts = plugin.getGameManager().countsForStats(killer.getUniqueId(), player.getUniqueId());
            if (ksp != null) {
                if (counts) {
                    ksp.recordKill();
                    plugin.getStatsManager().save(ksp);
                } else {
                    ksp.recordRoundKill();
                }
                killer.sendMessage(ChatColor.GOLD + "Убийство! Kills: " + ksp.getRoundKills());
            }
            // +1 к svokills убийце (objective создан в onEnable)
            if (counts) org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(),
                "scoreboard players add " + killer.getName() + " svokills 1");
        }

        event.setDeathMessage(null);

        String victimName = player.getName();
        String msg = ChatColor.RED + victimName + ChatColor.GRAY + " был убит";
        if (killer != null) {
            msg += ChatColor.GRAY + " игроком " + ChatColor.YELLOW + killer.getName();
        }
        plugin.getGameManager().broadcastGame(msg);

        // Если погибший держал ядерную кнопку - она выпадает на землю.
        plugin.getWardenManager().onNukeHolderDeath(player, event.getDrops(), player.getLocation());

        plugin.getGameManager().eliminatePlayer(player.getUniqueId(), true);

        // Обновляем сундуки после смерти каждого игрока (если игра ещё идёт)
        plugin.getGameManager().refreshChests();
    }
}
