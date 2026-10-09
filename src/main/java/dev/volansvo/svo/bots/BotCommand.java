package dev.volansvo.svo.bots;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.listeners.CommandListener;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

/** /asvobot list | why <ник> | dump <ник> | show <ник>|off | perf | removeall | reload - служебная команда для админа. */
public final class BotCommand implements CommandExecutor {

    private final VolanSVO plugin;

    public BotCommand(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!CommandListener.isBuilder(sender)) return true;
        BotManager bm = plugin.getBotManager();
        String sub = args.length == 0 ? "list" : args[0].toLowerCase();
        switch (sub) {
            case "removeall":
                bm.removeAll();
                sender.sendMessage(ChatColor.GREEN + "Все боты удалены.");
                return true;
            case "perf":
                for (String line : bm.perfLines()) sender.sendMessage(ChatColor.GRAY + line);
                return true;
            case "why": {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/asvobot why <ник бота>"); return true; }
                java.util.List<String> notes = bm.notesOf(args[1]);
                if (notes == null) { sender.sendMessage(ChatColor.RED + "Нет такого бота."); return true; }
                for (String line : bm.debugLines()) if (line.startsWith(args[1] + " ") || line.toLowerCase().startsWith(args[1].toLowerCase() + " "))
                    sender.sendMessage(ChatColor.GRAY + line);
                if (notes.isEmpty()) sender.sendMessage(ChatColor.GRAY + "Ничего особенного не было.");
                for (String n : notes) sender.sendMessage(ChatColor.YELLOW + n);
                return true;
            }
            case "dump": {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/asvobot dump <ник бота>"); return true; }
                java.io.File f = bm.dump(args[1], "по команде " + sender.getName());
                sender.sendMessage(f == null ? ChatColor.RED + "Нет такого бота (или файл не записался)."
                    : ChatColor.GREEN + "Журнал: plugins/VolanSVO/bot_dumps/" + f.getName());
                return true;
            }
            case "show": {
                if (!(sender instanceof org.bukkit.entity.Player)) { sender.sendMessage(ChatColor.RED + "Только в игре."); return true; }
                java.util.UUID me = ((org.bukkit.entity.Player) sender).getUniqueId();
                if (args.length < 2 || args[1].equalsIgnoreCase("off")) {
                    bm.watch(me, null);
                    sender.sendMessage(ChatColor.GRAY + "Показ выключен.");
                    return true;
                }
                if (!bm.watch(me, args[1])) { sender.sendMessage(ChatColor.RED + "Нет такого бота."); return true; }
                sender.sendMessage(ChatColor.GREEN + "Искры - путь, белый столб - цель, огонь - где ищет врага, зелёное - позиция в бою.");
                return true;
            }
            case "reload":
                plugin.reloadConfig();
                bm.reloadSkill();
                sender.sendMessage(ChatColor.GREEN + "Настройки ботов перечитаны (действуют для новых ботов).");
                return true;
            default:
                sender.sendMessage(ChatColor.GOLD + "Ботов: " + bm.count());
                for (String line : bm.debugLines()) sender.sendMessage(ChatColor.GRAY + line);
                return true;
        }
    }
}
