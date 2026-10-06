package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class SvoVoteCommand implements CommandExecutor {

    private final VolanSVO plugin;

    public SvoVoteCommand(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;
        plugin.getGameManager().castVote(player);
        return true;
    }
}
