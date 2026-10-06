package dev.volansvo.svo.bots;

import dev.volansvo.svo.VolanSVO;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Рация отряда (как команды в Fortnite): у игрока, в чьей команде есть боты, лежит предмет,
 * который нельзя выкинуть. ПКМ - следующий приказ (автономно / за мной / держать позицию),
 * ЛКМ - «атаковать цель»: враг, на которого смотрит игрок (или ближайший к взгляду).
 */
public final class SquadRadio implements Listener {

    public enum Mode {
        AUTO("Автономно", "Боты действуют сами"),
        FOLLOW("За мной", "Боты держатся рядом и прикрывают"),
        HOLD("Держать позицию", "Боты стоят здесь и отстреливаются"),
        LOOT("Лутать", "Боты лутают и приносят тебе хорошие вещи"),
        ATTACK("Атаковать цель", "Боты идут на указанного врага");

        final String title, hint;

        Mode(String title, String hint) { this.title = title; this.hint = hint; }
    }

    /** Приказ отряду (на команду). */
    public static final class Order {
        public Mode mode = Mode.AUTO;
        public Mode before = Mode.AUTO;   // куда вернуться после атаки
        public UUID commander;
        public Location hold;
        public UUID target;
        public int until;                 // тик сервера ботов, до которого действует атака
        public int version;
    }

    private final VolanSVO plugin;
    private final BotManager bots;
    private final VolanHooks hooks;
    private final NamespacedKey key;
    private final Map<Integer, Order> orders = new HashMap<Integer, Order>();

    SquadRadio(VolanSVO plugin, BotManager bots, VolanHooks hooks) {
        this.plugin = plugin;
        this.bots = bots;
        this.hooks = hooks;
        this.key = new NamespacedKey(plugin, "squad_radio");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        new BukkitRunnable() { @Override public void run() { maintain(); } }.runTaskTimer(plugin, 40L, 40L);
    }

    /** Приказ для команды или null (автономно). */
    Order order(int teamId) {
        if (teamId < 0) return null;
        Order o = orders.get(teamId);
        if (o == null || o.mode == Mode.AUTO) return null;
        return o;
    }

    void clear() { orders.clear(); }

    // =====================================================================  предмет

    ItemStack createRadio(Mode mode) {
        ItemStack it = new ItemStack(Material.RECOVERY_COMPASS);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Рация отряда" + ChatColor.GRAY + " - " + ChatColor.YELLOW + mode.title);
        m.setLore(Arrays.asList(
            ChatColor.GRAY + "ПКМ - сменить приказ ботам",
            ChatColor.GRAY + "   (автономно / за мной / держать позицию / лутать)",
            ChatColor.GRAY + "ЛКМ - атаковать врага, на которого смотришь",
            ChatColor.DARK_GRAY + "Выкинуть нельзя"));
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isRadio(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** У игроков с ботами в команде рация есть, у остальных её нет. */
    private void maintain() {
        boolean running = hooks.gameActive();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (bots.isBot(p)) continue;
            boolean need = running && hooks.inGame(p.getUniqueId()) && p.getGameMode() != GameMode.SPECTATOR
                && teamHasBots(p.getUniqueId());
            int slot = -1;
            for (int i = 0; i < p.getInventory().getSize(); i++) if (isRadio(p.getInventory().getItem(i))) { slot = i; break; }
            if (need && slot < 0) {
                Order o = orders.get(hooks.teamIdOf(p.getUniqueId()));
                ItemStack radio = createRadio(o == null ? Mode.AUTO : o.mode);
                int free = p.getInventory().firstEmpty();
                if (free >= 0) p.getInventory().setItem(free, radio);
            } else if (!need && slot >= 0) {
                p.getInventory().setItem(slot, null);
            }
            if (need && isRadio(p.getInventory().getItemInMainHand())) {
                Order o = orders.get(hooks.teamIdOf(p.getUniqueId()));
                p.sendActionBar(ChatColor.GOLD + "Приказ ботам: " + ChatColor.YELLOW + (o == null ? Mode.AUTO : o.mode).title);
            }
        }
        if (!running) orders.clear();
    }

    private boolean teamHasBots(UUID uid) {
        int team = hooks.teamIdOf(uid);
        if (team < 0) return false;
        for (UUID b : bots.botIds()) if (hooks.teamIdOf(b) == team && hooks.inGame(b)) return true;
        return false;
    }

    // =====================================================================  приказы

    private final Map<UUID, Integer> lastClick = new HashMap<UUID, Integer>();

    /** Один клик - одно действие (клиент шлёт сразу несколько событий на один клик). */
    private boolean debounce(Player p) {
        Integer last = lastClick.get(p.getUniqueId());
        int now = bots.now();
        if (last != null && now - last < 4) return false;
        lastClick.put(p.getUniqueId(), now);
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isRadio(e.getItem())) return;
        e.setCancelled(true);
        Action a = e.getAction();
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (debounce(e.getPlayer())) command(e.getPlayer(), left, null);
    }

    /** В технике взгляд упирается в её части: клик идёт по сущности, ловим и его. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseEntity(org.bukkit.event.player.PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isRadio(e.getPlayer().getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (debounce(e.getPlayer())) command(e.getPlayer(), false, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitWithRadio(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        if (!isRadio(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        LivingEntity hit = e.getEntity() instanceof Player && isEnemy(p, (Player) e.getEntity()) ? (LivingEntity) e.getEntity() : null;
        if (debounce(p)) command(p, true, hit);
    }

    /** ЛКМ в воздух, сидя в технике (интеракт может не прийти). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (!p.isInsideVehicle() || !isRadio(p.getInventory().getItemInMainHand())) return;
        if (debounce(p)) command(p, true, null);
    }

    private void command(Player p, boolean left, LivingEntity forced) {
        int team = hooks.teamIdOf(p.getUniqueId());
        if (team < 0 || !hooks.gameActive()) return;
        Order o = orders.get(team);
        if (o == null) { o = new Order(); orders.put(team, o); }
        o.commander = p.getUniqueId();
        if (left) {
            LivingEntity t = forced != null ? forced : pickTarget(p);
            if (t == null) { p.sendActionBar(ChatColor.RED + "Не вижу врага в прицеле"); return; }
            if (o.mode != Mode.ATTACK) o.before = o.mode;
            o.mode = Mode.ATTACK;
            o.target = t.getUniqueId();
            o.until = bots.now() + 20 * 90;
            announce(p, o, ChatColor.RED + "Атаковать: " + ChatColor.WHITE + t.getName());
        } else {
            Mode next;
            switch (o.mode) {
                case AUTO: next = Mode.FOLLOW; break;
                case FOLLOW: next = Mode.HOLD; break;
                case HOLD: next = Mode.LOOT; break;
                default: next = Mode.AUTO; break;
            }
            o.mode = next;
            o.target = null;
            if (next == Mode.HOLD) o.hold = p.getLocation().clone();
            announce(p, o, ChatColor.YELLOW + next.title + ChatColor.GRAY + " - " + next.hint);
            p.getInventory().setItemInMainHand(createRadio(next));
        }
    }

    private void announce(Player p, Order o, String text) {
        o.version++;
        p.sendActionBar(ChatColor.GOLD + "Приказ ботам: " + text);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1.4f);
        int team = hooks.teamIdOf(p.getUniqueId());
        for (UUID b : bots.botIds()) {
            if (hooks.teamIdOf(b) != team) continue;
            Player bp = Bukkit.getPlayer(b);
            if (bp != null && hooks.inGame(b)) bots.teamSay(bp, BotChatter.Topic.T_ACK, 0.6);
        }
    }

    /** Враг под прицелом: луч от глаз, иначе ближайший к линии взгляда в пределах 120 блоков. */
    private LivingEntity pickTarget(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTrace(eye, eye.getDirection(), 120, org.bukkit.FluidCollisionMode.NEVER, true, 0.6,
            en -> en instanceof Player && !en.equals(p) && isEnemy(p, (Player) en));
        if (r != null && r.getHitEntity() instanceof LivingEntity) return (LivingEntity) r.getHitEntity();
        LivingEntity best = null;
        double bestAng = Math.toRadians(12);
        for (Player o : hooks.alivePlayers()) {
            if (o.equals(p) || !o.getWorld().equals(p.getWorld()) || !isEnemy(p, o)) continue;
            org.bukkit.util.Vector to = o.getEyeLocation().toVector().subtract(eye.toVector());
            if (to.length() > 120) continue;
            double ang = to.angle(eye.getDirection());
            if (ang < bestAng) { bestAng = ang; best = o; }
        }
        return best;
    }

    private boolean isEnemy(Player me, Player o) {
        return o.getGameMode() == GameMode.SURVIVAL && hooks.inGame(o.getUniqueId()) && !hooks.sameTeam(me.getUniqueId(), o.getUniqueId());
    }

    // =====================================================================  нельзя выкинуть

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isRadio(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isRadio(e.getMainHandItem()) || isRadio(e.getOffHandItem())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        boolean radio = isRadio(e.getCurrentItem()) || isRadio(e.getCursor());
        if (!radio && e.getHotbarButton() >= 0 && e.getWhoClicked() instanceof Player) {
            radio = isRadio(((Player) e.getWhoClicked()).getInventory().getItem(e.getHotbarButton()));
        }
        if (!radio) return;
        // Внутри своего инвентаря перекладывать можно, в сундук и т.п. - нет.
        if (e.getView().getTopInventory().getType() != InventoryType.CRAFTING || e.getClick().isKeyboardClick() && e.getClick().name().contains("DROP")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (isRadio(e.getOldCursor()) && e.getView().getTopInventory().getType() != InventoryType.CRAFTING) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(this::isRadio);
    }

    @SuppressWarnings("unused")
    private static boolean alive(Entity e) { return e != null && e.isValid() && !e.isDead(); }
}
