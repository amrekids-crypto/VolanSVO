package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /svoteam               - открыть GUI выбора команды
 * /svoteam approve <ник> - одобрить заявку игрока в твою команду
 * /svoteam leave         - выйти из команды
 * /svoteam ready         - «Начинаем»: готов к старту (готовы все - формирование заканчивается)
 */
public class SvoTeamCommand implements CommandExecutor, TabCompleter {

    private final VolanSVO plugin;

    public SvoTeamCommand(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;

        if (args.length == 0) {
            plugin.getGameManager().openTeamGui(player);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("approve") || sub.equals("accept") || sub.equals("yes") || sub.equals("одобрить")) {
            if (args.length < 2) { player.sendMessage(ChatColor.RED + "/svoteam approve <ник>"); return true; }
            plugin.getGameManager().approveJoin(player, args[1]);
        } else if (sub.equals("leave") || sub.equals("выйти")) {
            plugin.getGameManager().formationLeave(player);
        } else if (sub.equals("ready") || sub.equals("готов")) {
            plugin.getGameManager().formationReady(player);
        } else {
            plugin.getGameManager().openTeamGui(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> r = new ArrayList<String>();
        if (args.length == 1) {
            for (String s : new String[]{"approve", "leave", "ready"}) {
                if (s.startsWith(args[0].toLowerCase())) r.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("approve")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) r.add(p.getName());
            }
        }
        return r;
    }
}
