package dev.volansvo.svo.commands;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collections;
import java.util.List;

public class YanykCommand implements CommandExecutor, TabCompleter {
    private final VolanSVO plugin;

    public YanykCommand(VolanSVO plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!dev.volansvo.svo.listeners.CommandListener.isBuilder(sender)) return true;
        if (!(sender instanceof Player)) { sender.sendMessage("Только для игроков."); return true; }
        Player player = (Player) sender;
        Location spawnLoc = player.getLocation();

        if (args.length >= 3) {
            try {
                double x = Double.parseDouble(args[0]);
                double y = Double.parseDouble(args[1]);
                double z = Double.parseDouble(args[2]);
                spawnLoc = new Location(player.getWorld(), x + 0.5, y, z + 0.5);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Координаты: /asvoyanyk [x y z]");
                return true;
            }
        }

        final World world = spawnLoc.getWorld();
        final Zombie yanyk = (Zombie) world.spawnEntity(spawnLoc, EntityType.ZOMBIE);
        yanyk.setCustomName(ChatColor.GOLD + "Янукович - сын Зевса");
        yanyk.setCustomNameVisible(true);
        // Тэги: yaniksvo + yaniksvo_sleep. Глобальный watcher в GameManager их ищет.
        yanyk.addScoreboardTag("yaniksvo");
        yanyk.addScoreboardTag("yaniksvo_sleep");
        yanyk.setPersistent(true);
        yanyk.setRemoveWhenFarAway(false);
        yanyk.setCanPickupItems(true);
        yanyk.setSilent(true);
        yanyk.setAI(false);
        yanyk.setInvulnerable(false);
        yanyk.addPotionEffect(new PotionEffect(
            PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, true, false, false));
        EntityEquipment eq = yanyk.getEquipment();
        if (eq != null) {
            eq.setHelmet(new ItemStack(Material.GOLDEN_HELMET));
            eq.setChestplate(new ItemStack(Material.GOLDEN_CHESTPLATE));
            eq.setLeggings(new ItemStack(Material.GOLDEN_LEGGINGS));
            eq.setBoots(new ItemStack(Material.GOLDEN_BOOTS));
            eq.setHelmetDropChance(0f);
            eq.setChestplateDropChance(0f);
            eq.setLeggingsDropChance(0f);
            eq.setBootsDropChance(0f);
        }
        AttributeInstance hp = yanyk.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) { hp.setBaseValue(50.0); yanyk.setHealth(50.0); }

        player.sendMessage(ChatColor.GREEN + "Янукович заспавнен на "
            + (int) spawnLoc.getX() + " " + (int) spawnLoc.getY() + " " + (int) spawnLoc.getZ()
            + ". Просыпается когда svoplayer ближе 30 блоков.");

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
