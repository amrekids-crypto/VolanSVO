package dev.volansvo.svo.managers;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class WardenManager {

    private final VolanSVO plugin;

    private Warden warden      = null;
    private boolean wardenDead = false;
    private boolean rocketLaunched = false;

    /** UUID последнего игрока, нанёсшего урон вардену. */
    private UUID lastDamager = null;
    /** UUID текущего владельца ядерной кнопки (null = на земле / режим "всем"). */
    private UUID nukeOwnerUid = null;
    /** true = кнопка выдана всем живым (владелец вышел), кто первый запустит - победит. */
    private boolean nukeFreeForAll = false;

    /** Координаты спавна - нужны для анти-зарывания. */
    private double spawnX = 0, spawnY = 0, spawnZ = 0;

    public WardenManager(VolanSVO plugin) {
        this.plugin = plugin;
    }

    public void spawnWarden(World world) {
        spawnWarden(world,
            plugin.getConfig().getDouble("warden.spawn_x", -565),
            plugin.getConfig().getDouble("warden.spawn_y", 102),
            plugin.getConfig().getDouble("warden.spawn_z", 479));
    }

    public void spawnWarden(World world, double x, double y, double z) {
        spawnX = x;
        spawnY = y;
        spawnZ = z;
        Location loc = new Location(world, spawnX, spawnY, spawnZ);

        warden = (Warden) world.spawnEntity(loc, EntityType.WARDEN);
        warden.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + wardenName());
        warden.setCustomNameVisible(true);
        warden.addScoreboardTag("svozir");
        AttributeInstance hp = warden.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) {
            hp.setBaseValue(500.0);
            warden.setHealth(500.0);
        }
        warden.setRemoveWhenFarAway(false);
        // Сразу даём бесконечный glowing
        warden.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, true, false));

        wardenDead      = false;
        rocketLaunched  = false;
        lastDamager     = null;
        nukeOwnerUid    = null;
        nukeFreeForAll  = false;

        plugin.getBossbarManager().setupWardenBar(warden);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (wardenDead) { cancel(); return; }
                if (warden == null || !warden.isValid()) {
                    // Чанк выгрузился и загрузился заново (новый объект) - находим его по тегу.
                    Warden found = null;
                    if (warden != null && warden.getWorld() != null) {
                        for (Warden w : warden.getWorld().getEntitiesByClass(Warden.class)) {
                            if (w.getScoreboardTags().contains("svozir") && w.isValid()) { found = w; break; }
                        }
                    }
                    if (found == null) {
                        plugin.getBossbarManager().hideWardenBar();
                        if (++missingSeconds >= 5) { releaseTicket(); cancel(); }
                        return;
                    }
                    warden = found;
                }
                missingSeconds = 0;
                // Варден без цели через минуту закапывается и исчезает (ванильное поведение),
                // а босбар оставался. Не даём ему уйти под землю.
                try {
                    net.minecraft.world.entity.monster.warden.WardenAi.setDigCooldown(
                        ((org.bukkit.craftbukkit.entity.CraftLivingEntity) warden).getHandle());
                } catch (Throwable ignored) {}
                // Держим его чанк загруженным, даже если рядом никого нет.
                keepTicket(warden.getLocation());
                plugin.getBossbarManager().updateWardenBar(warden);
                // Поддерживаем бесконечный glowing (на случай если истёк)
                if (!warden.hasPotionEffect(PotionEffectType.GLOWING)) {
                    warden.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, true, false));
                }
                // Анти-зарывание: если опустился ниже spawnY - 3, тп обратно
                if (warden.getLocation().getY() < spawnY - 3) {
                    warden.teleport(new Location(warden.getWorld(), spawnX, spawnY, spawnZ));
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private int missingSeconds;
    private org.bukkit.Chunk ticketChunk;

    private void keepTicket(Location l) {
        org.bukkit.Chunk c = l.getChunk();
        if (c.equals(ticketChunk)) return;
        releaseTicket();
        c.addPluginChunkTicket(plugin);
        ticketChunk = c;
    }

    private void releaseTicket() {
        if (ticketChunk != null) {
            try { ticketChunk.removePluginChunkTicket(plugin); } catch (Throwable ignored) {}
            ticketChunk = null;
        }
    }

    /** Вызывается из EntityListener каждый раз, когда игрок наносит урон вардену. */
    public void onWardenDamaged(Player damager) {
        if (warden == null || wardenDead) return;
        lastDamager = damager.getUniqueId();
    }

    public void onWardenDeath(EntityDeathEvent event) {
        if (warden == null) return;
        wardenDead = true;
        releaseTicket();
        plugin.getBossbarManager().hideWardenBar();

        // Определяем убийцу: сначала стандартный getKiller(), иначе lastDamager
        Player killer = event.getEntity().getKiller();
        if (killer == null && lastDamager != null) {
            killer = Bukkit.getPlayer(lastDamager);
        }

        warden = null;

        GameManager gm = plugin.getGameManager();
        final String wn = wardenName();
        if (killer != null && gm.isPlayerInGame(killer.getUniqueId())) {
            // Убийце - ядерная кнопка ВМЕСТО предмета в руке.
            nukeOwnerUid = killer.getUniqueId();
            nukeFreeForAll = false;
            killer.addScoreboardTag("nearzir");
            gm.giveNuclearButton(killer);

            killer.sendTitle(ChatColor.DARK_RED + wn + " мёртв!",
                ChatColor.GOLD + "У тебя ☢ ЯДЕРНАЯ КНОПКА ☢", 10, 70, 20);
            killer.sendMessage(ChatColor.DARK_RED + wn + " мёртв! " + ChatColor.YELLOW
                + "Тебе выдана " + ChatColor.RED + "☢ ЯДЕРНАЯ КНОПКА ☢" + ChatColor.YELLOW
                + " - ПКМ чтобы выбрать цель и запустить.");
            for (Player p : gm.getActivePlayers()) {
                if (!p.getUniqueId().equals(nukeOwnerUid)) {
                    p.sendMessage(ChatColor.DARK_RED + wn + " мёртв. " + ChatColor.YELLOW
                        + killer.getName() + " получил ядерную кнопку!");
                }
            }
        } else {
            // Убийца не найден / не в игре - кнопка сразу всем живым (кто первый - победит).
            for (Player p : gm.getActivePlayers()) {
                p.sendMessage(ChatColor.DARK_RED + wn + " мёртв!");
            }
            doNukeFreeForAll();
        }
    }

    /** Имя босса-вардена для активной карты (svo=Жириновский, east=Нетаньяху). */
    private String wardenName() {
        dev.volansvo.svo.maps.MapData m = plugin.getMapManager().getActiveMap();
        return (m != null) ? m.getWardenName() : "Жириновский";
    }

    /**
     * Можно ли игроку запустить ракету прямо сейчас. Право даёт ВЛАДЕНИЕ ядерной кнопкой.
     * Отправляет сообщение об ошибке если нет.
     */
    public boolean canLaunch(Player player) {
        if (!wardenDead) {
            player.sendMessage(ChatColor.RED + wardenName() + " ещё жив!");
            return false;
        }
        if (rocketLaunched) {
            player.sendMessage(ChatColor.RED + "Ракета уже была запущена.");
            return false;
        }
        if (!plugin.getGameManager().hasNukeButton(player)) {
            player.sendMessage(ChatColor.RED + "Запустить ракету может только владелец "
                + ChatColor.DARK_RED + "☢ ЯДЕРНОЙ КНОПКИ.");
            return false;
        }
        return true;
    }

    /** Подобрал кнопку с земли - становится владельцем (если не режим "всем"). */
    public void onNukePickup(UUID uid) {
        if (rocketLaunched || !wardenDead || nukeFreeForAll) return;
        nukeOwnerUid = uid;
    }

    /** Хаос поменял инвентари местами: кнопка переехала - владелец тоже. */
    public void onInventoriesSwapped(Player a, Player b) {
        if (rocketLaunched || !wardenDead || nukeFreeForAll) return;
        GameManager gm = plugin.getGameManager();
        if (gm.hasNukeButton(a)) nukeOwnerUid = a.getUniqueId();
        else if (gm.hasNukeButton(b)) nukeOwnerUid = b.getUniqueId();
    }

    /** Кнопку выбросили вручную - владельца больше нет (на земле, подберёт - станет владельцем). */
    public void onNukeDropped(UUID uid) {
        if (nukeOwnerUid != null && nukeOwnerUid.equals(uid)) nukeOwnerUid = null;
    }

    /**
     * Владелец кнопки вышел из игры/мира - кнопка достаётся всем живым (кто первый - победит).
     * Срабатывает только если уходит ИМЕННО владелец (если кнопка на земле/у мёртвого - нет).
     */
    public void onNukeOwnerLeft(UUID uid) {
        if (rocketLaunched || !wardenDead) return;
        if (nukeOwnerUid == null || !nukeOwnerUid.equals(uid)) return;
        doNukeFreeForAll();
    }

    /**
     * Владелец кнопки погиб - кнопка выпадает на землю (стойкая), владельца нет.
     * Вызывается из DeathListener. В режиме "всем" - не трогаем (кнопки и так у всех).
     */
    public void onNukeHolderDeath(Player player, List<ItemStack> drops, Location loc) {
        if (rocketLaunched || !wardenDead || nukeFreeForAll) return;
        GameManager gm = plugin.getGameManager();
        boolean holder = gm.hasNukeButton(player) || gm.dropsContainNuke(drops);
        if (!holder) return;
        // Убираем все кнопки из дропа и инвентаря, кладём ОДНУ стойкую на землю.
        for (Iterator<ItemStack> it = drops.iterator(); it.hasNext(); ) {
            if (gm.isNukeButton(it.next())) it.remove();
        }
        gm.removeNukeButtons(player);
        try {
            Item dropped = loc.getWorld().dropItem(loc, gm.createNukeButton());
            dropped.setInvulnerable(true);     // не сгорит в огне/лаве
            dropped.setPickupDelay(10);        // секунда до подбора - заметить успеют
        } catch (Throwable ignored) {}
        nukeOwnerUid = null; // на земле - подберёт кто-нибудь, станет владельцем
        for (Player p : gm.getActivePlayers()) {
            p.sendMessage(ChatColor.DARK_RED + "Ядерная кнопка выпала из " + player.getName() + "! "
                + ChatColor.YELLOW + "Успей подобрать!");
        }
    }

    private void doNukeFreeForAll() {
        if (rocketLaunched || nukeFreeForAll) return;
        nukeFreeForAll = true;
        nukeOwnerUid = null;
        GameManager gm = plugin.getGameManager();
        gm.giveNukeButtonToAllLiving();
        for (Player p : gm.getActivePlayers()) {
            p.sendTitle(ChatColor.DARK_RED + "☢ ЯДЕРНАЯ КНОПКА ☢",
                ChatColor.YELLOW + "Кто первый запустит - тот победит!", 10, 70, 20);
            p.sendMessage(ChatColor.DARK_RED + "Ядерная кнопка у всех живых! " + ChatColor.YELLOW
                + "ПКМ по кнопке - кто первый запустит, тот победит!");
        }
    }

    public boolean launchRocket(Player player, String target) {
        if (!canLaunch(player)) return false;

        rocketLaunched = true;

        String display = target.toLowerCase();
        if (display.equals("washington"))   display = "Вашингтон";
        else if (display.equals("moscow"))  display = "Москву";
        else if (display.equals("kyiv"))    display = "Киев";
        else if (display.equals("lnr"))     display = "ЛНР";

        final UUID winnerUid = player.getUniqueId();
        for (Player p : plugin.getGameManager().getAllSvoPlayers()) {
            p.sendTitle(ChatColor.DARK_RED + "РАКЕТА",
                ChatColor.GOLD + player.getName() + " на " + display, 10, 80, 20);
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.5f);
        }

        new BukkitRunnable() {
            @Override public void run() { plugin.getGameManager().endGame(winnerUid); }
        }.runTaskLater(plugin, 100L);

        return true;
    }

    public void removeWarden() {
        releaseTicket();
        if (warden != null && warden.isValid()) warden.remove();
        warden        = null;
        wardenDead    = false;
        rocketLaunched = false;
        lastDamager   = null;
        nukeOwnerUid  = null;
        nukeFreeForAll = false;
    }

    public Warden getWarden() { return isWardenAlive() ? warden : null; }

    public boolean isWardenAlive() {
        return warden != null && warden.isValid() && !wardenDead;
    }

    public void forceKillWarden() {
        if (warden == null || !warden.isValid()) return;
        warden.damage(575.0);
    }
}
