package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

/**
 * /asvotptolobbyst [x y z]
 * Создаёт невидимый арморстенд-портал. Каждую секунду игроки в радиусе 2 блоков
 * от него выполняют /svolobby (телепорт в лобби СВО и всё сопутствующее).
 * Стенд ищется глобальным watcher-ом по тегу svolobbyst.
 */
public class TpToLobbyStandCommand implements CommandExecutor, TabCompleter {
    private final VolanSVO plugin;

    public TpToLobbyStandCommand(VolanSVO plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!dev.volansvo.svo.listeners.CommandListener.isBuilder(sender)) return true;
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;
        Location loc = player.getLocation();

        if (args.length >= 3) {
            try {
                double x = Double.parseDouble(args[0]);
                double y = Double.parseDouble(args[1]);
                double z = Double.parseDouble(args[2]);
                loc = new Location(player.getWorld(), x + 0.5, y, z + 0.5);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Координаты: /asvotptolobbyst [x y z]");
                return true;
            }
        }

        World world = loc.getWorld();
        ArmorStand stand = (ArmorStand) world.spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setMarker(true);            // без хитбокса, не мешает
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setBasePlate(false);
        stand.setArms(false);
        stand.setCustomName(ChatColor.AQUA + "Встать в очередь");
        stand.setCustomNameVisible(true);
        stand.setPersistent(true);
        stand.setRemoveWhenFarAway(false);
        stand.addScoreboardTag("svolobbyst");

        player.sendMessage(ChatColor.GREEN + "Стенд-портал в лобби создан на "
            + (int) loc.getX() + " " + (int) loc.getY() + " " + (int) loc.getZ()
            + ". Игроки в радиусе 2 блоков отправляются в лобби.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        Player player = (Player) sender;
        Block target = player.getTargetBlockExact(50);
        if (target == null) return Collections.emptyList();
        switch (args.length) {
            case 1: return Collections.singletonList(String.valueOf(target.getX()));
            case 2: return Collections.singletonList(String.valueOf(target.getY()));
            case 3: return Collections.singletonList(String.valueOf(target.getZ()));
            default: return Collections.emptyList();
        }
    }
}
