package dev.volansvo.svo.listeners;

import dev.volansvo.svo.GameState;
import dev.volansvo.svo.VolanSVO;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class EntityListener implements Listener {

    private final VolanSVO plugin;

    public EntityListener(VolanSVO plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();

        // Янык - доп. дропает хорошо зачарованный золотой нагрудник (стандартный лут зомби сохраняется)
        if (entity instanceof Zombie && entity.getScoreboardTags().contains("yaniksvo")) {
            event.setDroppedExp(20);
            ItemStack chestplate = new ItemStack(Material.GOLDEN_CHESTPLATE);
            ItemMeta meta = chestplate.getItemMeta();
            if (meta != null) {
                meta.addEnchant(Enchantment.PROTECTION, 4, true);
                meta.addEnchant(Enchantment.UNBREAKING, 3, true);
                meta.addEnchant(Enchantment.THORNS, 3, true);
                meta.addEnchant(Enchantment.MENDING, 1, true);
                chestplate.setItemMeta(meta);
            }
            event.getDrops().add(chestplate);

            // После того как drop-ы зеспавнятся как Item-ы - находим нагрудник
            // и делаем его invulnerable (огнеупорным).
            final org.bukkit.Location deathLoc = entity.getLocation();
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    for (Entity e : deathLoc.getWorld().getNearbyEntities(deathLoc, 4, 4, 4)) {
                        if (!(e instanceof Item)) continue;
                        Item item = (Item) e;
                        if (item.getItemStack().getType() != Material.GOLDEN_CHESTPLATE) continue;
                        item.setInvulnerable(true); // не сгорит в огне/лаве
                    }
                }
            }, 2L);
            return;
        }

        if (plugin.getGameManager().getState() != GameState.ACTIVE) return;
        if (entity instanceof Warden && entity.getScoreboardTags().contains("svozir")) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            plugin.getWardenManager().onWardenDeath(event);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (plugin.getGameManager().getState() != GameState.ACTIVE) return;
        Entity entity = event.getEntity();

        // Динамит "рядом рвануло" (Хаос) - капаем урон ДО его применения, чтобы не убивало
        // с одного взрыва (4 сердца для игрока без брони, с бронёй будет меньше - она снижает
        // урон как обычно поверх этого потолка). Именно капаем событие, а не лезем в setHealth()
        // после взрыва - та ручная правка здоровья постфактум и ломала респавн раньше.
        if (entity instanceof Player
                && event.getDamager().getScoreboardTags().contains(dev.volansvo.svo.managers.ChaosManager.BLAST_TNT_TAG)
                && event.getDamage() > dev.volansvo.svo.managers.ChaosManager.BLAST_TNT_DAMAGE_CAP) {
            event.setDamage(dev.volansvo.svo.managers.ChaosManager.BLAST_TNT_DAMAGE_CAP);
        }

        // FRIENDLY FIRE: урон между членами одной команды (Дуо/Трио) запрещён.
        // Второй слой поверх Team.setAllowFriendlyFire(false) - на случай источников
        // урона, которые ванильный friendly-fire флаг не покрывает.
        if (entity instanceof Player) {
            Player victim = (Player) entity;
            Player attacker = extractPlayerDamager(event.getDamager());
            if (attacker != null
                    && plugin.getGameManager().sameTeam(attacker.getUniqueId(), victim.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            // Запоминаем последнего дамагера - для засчёта килла при combat-log/уходе из арены.
            if (attacker != null && !event.isCancelled()) {
                plugin.getGameManager().recordCombat(victim.getUniqueId(), attacker.getUniqueId());
            }
        }

        // Урон по вардену - отслеживаем для nearzir
        if (entity instanceof Warden && entity.getScoreboardTags().contains("svozir")) {
            Player damager = extractPlayerDamager(event.getDamager());
            if (damager != null) plugin.getWardenManager().onWardenDamaged(damager);
        }
        // Урон по airpig от entity - сбиваем
        if (entity instanceof ArmorStand && entity.getScoreboardTags().contains("airpig")) {
            event.setCancelled(true);
            plugin.getAirdropManager().onAirpigShot((ArmorStand) entity);
        }

        // Хаос: удар по бойцу ХЕЗБОЛЛЫ/МЕЦАХ - вся банда агрится на ударившего на 1 мин.
        if (entity.getScoreboardTags().contains(dev.volansvo.svo.managers.ChaosManager.FACTION_TAG)) {
            Player attacker = extractPlayerDamager(event.getDamager());
            if (attacker != null) {
                plugin.getChaosManager().markHatedByVictimFaction(entity, attacker.getUniqueId());
            }
        }
    }

    /** Сбили airpig любым другим путём (взрыв, огонь и т.п.). */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAirpigDamage(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent) return; // обработали выше
        if (!(event.getEntity() instanceof ArmorStand)) return;
        ArmorStand stand = (ArmorStand) event.getEntity();
        if (!stand.getScoreboardTags().contains("airpig")) return;
        event.setCancelled(true);
        plugin.getAirdropManager().onAirpigShot(stand);
    }

    /**
     * Хаос: банды ХЕЗБОЛЛА/МЕЦАХ игнорируют игроков - гасим авто-таргетинг ванильного AI
     * на игрока. Ручное перенацеливание друг на друга (ChaosManager.tickFactionTargeting)
     * ставит цель НЕ игрока (кроме случая ниже), так что под это условие не попадает.
     * ИСКЛЮЧЕНИЕ: если игрок недавно ударил эту банду - она "мстит" ему 1 мин
     * (см. markHatedByVictimFaction) - тогда таргетинг на него не гасим.
     */
    @EventHandler(ignoreCancelled = false)
    public void onFactionTarget(EntityTargetEvent event) {
        Entity entity = event.getEntity();
        if (entity == null) return;
        if (!entity.getScoreboardTags().contains(dev.volansvo.svo.managers.ChaosManager.FACTION_TAG)) return;
        if (event.getTarget() instanceof Player) {
            Player target = (Player) event.getTarget();
            if (!plugin.getChaosManager().isHatedByEntityFaction(entity, target)) {
                event.setCancelled(true);
            }
        }
    }

    private Player extractPlayerDamager(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile) {
            Projectile proj = (Projectile) damager;
            if (proj.getShooter() instanceof Player) return (Player) proj.getShooter();
        }
        return null;
    }
}
