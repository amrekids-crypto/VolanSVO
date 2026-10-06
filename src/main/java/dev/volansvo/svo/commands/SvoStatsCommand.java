package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

public class SvoStatsCommand implements CommandExecutor {
    private final VolanSVO plugin;
    public SvoStatsCommand(VolanSVO plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;

        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        int games    = readScore(board, "svogames",    player);
        int wins     = readScore(board, "svowins",     player);
        int kills    = readScore(board, "svokills",    player);
        int winrate  = readScore(board, "svowinrate",  player);

        // Формула из оригинальных командных блоков лобби (smoothed):
        //   sviwinratedelit = svogames + 20
        //   svowinrate = (svowins * 100) / sviwinratedelit + 10
        // Считаем сами и СИНХРОНИЗИРУЕМ objective svowinrate (самоисправление
        // старых неверных значений, где winrate был равен числу побед).
        if (games >= 3) {
            int delit = games + 20;
            winrate = (wins * 100) / delit + 10;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "scoreboard players set " + player.getName() + " svowinrate " + winrate);
        }

        String wrStr = (games >= 3) ? (winrate + "%") : "нет данных (нужно 3+ игр)";

        player.sendMessage(ChatColor.GOLD + "--- Статистика СВО (" + player.getName() + ") ---");
        player.sendMessage(ChatColor.GRAY + "Игр: "                   + ChatColor.WHITE + games);
        player.sendMessage(ChatColor.GRAY + "Побед: "                 + ChatColor.YELLOW + wins);
        player.sendMessage(ChatColor.GRAY + "Убийства с руки/пушек: " + ChatColor.RED + kills);
        player.sendMessage(ChatColor.GRAY + "Винрейт: "               + ChatColor.AQUA + wrStr);
        return true;
    }

    private int readScore(Scoreboard board, String objName, Player player) {
        Objective obj = board.getObjective(objName);
        if (obj == null) return 0;
        Score s = obj.getScore(player.getName());
        if (!s.isScoreSet()) return 0;
        return s.getScore();
    }
}
