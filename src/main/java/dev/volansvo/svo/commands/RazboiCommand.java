package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.managers.GameManager;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.List;

/**
 * Ставит невидимый стенд-триггер разбойников (тег svorazboist) - тот же механизм,
 * что и стенды, размещённые на карте вручную (см. GameManager.handleBanditStands).
 * Стенд сработает САМ, когда во время активной игры рядом (30 бл) окажется участвующий
 * в ней игрок (survival + тег svoplayer) - тогда заспавнятся бандиты с именем по карте
 * (ХАМАС на east, ЛНРовец на svo, иначе Разбойник/Карательный отряд).
 */
public class RazboiCommand implements CommandExecutor, TabCompleter {

    private final VolanSVO plugin;

    private static final String USAGE = ChatColor.YELLOW + "/asvorazboi <топорщики> <арбалетчики>";

    public RazboiCommand(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!dev.volansvo.svo.listeners.CommandListener.isBuilder(sender)) return true;
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(USAGE);
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Нужно указать оба числа.");
            player.sendMessage(USAGE);
            return true;
        }

        int vindCount, pilCount;
        try {
            vindCount = Integer.parseInt(args[0]); // топорщики - Vindicator
            pilCount  = Integer.parseInt(args[1]); // арбалетчики - Pillager
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.RED + "Оба аргумента - числа.");
            player.sendMessage(USAGE);
            return true;
        }
        if (vindCount < 0 || pilCount < 0) {
            player.sendMessage(ChatColor.RED + "Числа не могут быть отрицательными.");
            player.sendMessage(USAGE);
            return true;
        }

        World world = player.getWorld();
        Location loc = player.getLocation();

        ArmorStand stand = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setMarker(true);
        stand.setInvulnerable(true);
        stand.setRemoveWhenFarAway(false);
        stand.addScoreboardTag("svorazboist");
        stand.getPersistentDataContainer().set(
            new NamespacedKey(plugin, GameManager.RAZBOI_VIND_KEY), PersistentDataType.INTEGER, vindCount);
        stand.getPersistentDataContainer().set(
            new NamespacedKey(plugin, GameManager.RAZBOI_PIL_KEY), PersistentDataType.INTEGER, pilCount);

        player.sendMessage(ChatColor.GREEN + "Стенд разбойников поставлен (топорщиков: " + vindCount
            + ", арбалетчиков: " + pilCount + "). Сработает когда рядом (в игре) окажется игрок.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 || args.length == 2) return Collections.singletonList("1");
        return Collections.emptyList();
    }
}
