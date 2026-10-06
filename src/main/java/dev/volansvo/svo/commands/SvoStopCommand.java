package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.ChatColor;
import org.bukkit.command.*;

public class SvoStopCommand implements CommandExecutor {
    private final VolanSVO plugin;
    public SvoStopCommand(VolanSVO plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!dev.volansvo.svo.listeners.CommandListener.isBuilder(sender)) return true;
        plugin.getGameManager().forceStopAndDelete();
        sender.sendMessage(ChatColor.RED + "СВО остановлено, мир удаляется.");
        return true;
    }
}
