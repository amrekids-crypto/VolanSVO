package dev.volansvo.svo.commands;

import dev.volansvo.svo.GameState;
import dev.volansvo.svo.VolanSVO;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LaunchSvoCommand implements CommandExecutor, TabCompleter {

    private final VolanSVO plugin;
    private static final List<String> TARGETS = Arrays.asList("washington", "moscow", "kyiv", "lnr");

    public LaunchSvoCommand(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;

        if (plugin.getGameManager().getState() != GameState.ACTIVE) {
            player.sendMessage(ChatColor.RED + "Игра не идёт.");
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatColor.YELLOW + "/svolaunch <washington|moscow|kyiv|lnr>");
            return true;
        }
        String t = args[0].toLowerCase();
        if (!TARGETS.contains(t)) {
            player.sendMessage(ChatColor.RED + "Цели: washington, moscow, kyiv, lnr");
            return true;
        }
        plugin.getWardenManager().launchRocket(player, t);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new ArrayList<String>();
            String prefix = args[0].toLowerCase();
            for (String t : TARGETS) {
                if (t.startsWith(prefix)) matches.add(t);
            }
            Collections.sort(matches);
            return matches;
        }
        return Collections.emptyList();
    }
}
