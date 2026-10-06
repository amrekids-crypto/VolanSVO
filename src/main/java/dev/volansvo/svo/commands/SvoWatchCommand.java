package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class SvoWatchCommand implements CommandExecutor {
    private final VolanSVO plugin;
    public SvoWatchCommand(VolanSVO plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        String target = args.length >= 1 ? args[0] : null;
        plugin.getGameManager().startSpectating((Player) sender, target);
        return true;
    }
}
