package dev.volansvo.svo.listeners;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public class CommandListener implements Listener {

    private final VolanSVO plugin;

    /** Только эти команды режутся для не-OP без svobuilder. */
    private static final Set<String> BUILDER_ONLY = new HashSet<String>(
        Arrays.asList("asvorazboi", "asvostop", "asvoyanyk", "asvonew", "asvomap", "asvotptolobbyst", "asvobot")
    );

    public CommandListener(VolanSVO plugin) {
        this.plugin = plugin;
    }

    /**
     * Право на админ-команды СВО: OP или тег svobuilder.
     * Вызывать в executor'ах админ-команд (defense-in-depth поверх preprocess-фильтра).
     */
    public static boolean isBuilder(org.bukkit.command.CommandSender sender) {
        if (!(sender instanceof Player)) return true; // консоль/RCON - можно
        Player p = (Player) sender;
        if (p.isOp()) return true;
        if (p.getScoreboardTags().contains("svobuilder")) return true;
        p.sendMessage(ChatColor.RED + "Нет прав (нужен OP или тег svobuilder).");
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String raw = event.getMessage().toLowerCase().trim();
        if (!raw.startsWith("/")) return;
        String base = raw.substring(1).split(" ")[0];
        // ВАЖНО: срезаем namespace (minecraft:/volansvo:), иначе /volansvo:asvostop обходил фильтр.
        if (base.contains(":")) base = base.substring(base.indexOf(':') + 1);

        if (!BUILDER_ONLY.contains(base)) return; // svoplay/svolobby/svostats/svolaunch/svovote - всем
        if (player.isOp()) return;

        boolean hasBuilder = player.getScoreboardTags().contains("svobuilder");
        if (!hasBuilder) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "Нет тега svobuilder.");
        }
    }

    /** Команды, которые не показываем в таб-подсказке (служебные/кликабельные). */
    private static final Set<String> HIDDEN = new HashSet<String>(
        Arrays.asList("svovote", "svowatch")
    );

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommandSend(PlayerCommandSendEvent event) {
        Iterator<String> it = event.getCommands().iterator();
        while (it.hasNext()) {
            String cmd = it.next();
            String base = cmd.contains(":") ? cmd.substring(cmd.indexOf(':') + 1) : cmd;
            if (HIDDEN.contains(base.toLowerCase())) it.remove();
        }
    }
}
