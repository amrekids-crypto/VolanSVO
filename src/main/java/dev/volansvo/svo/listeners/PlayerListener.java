package dev.volansvo.svo.listeners;

import dev.volansvo.svo.GameState;
import dev.volansvo.svo.VolanSVO;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerListener implements Listener {

    private final VolanSVO plugin;

    public PlayerListener(VolanSVO plugin) {
        this.plugin = plugin;
        // Periodically purge queue from players who lost insvo tag
        new BukkitRunnable() {
            @Override
            public void run() {
                plugin.getGameManager().purgeQueue();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        plugin.getStatsManager().getOrCreate(player);
        // Боссбар - только участникам СВО (переподключившимся с тегами), не всем зашедшим на сервер.
        if (plugin.getGameManager().hasSvoTags(player)) {
            plugin.getBossbarManager().addPlayerToAll(player);
        }

        // Игрок зашёл с тегами СВО, но игра НЕ идёт (остались после краша/рестарта).
        if (!plugin.getGameManager().isGameRunning()
                && !plugin.getGameManager().isInQueue(player)
                && plugin.getGameManager().hasSvoTags(player)) {
            World lobby = plugin.getWorldManager().getLobbyWorld();
            World game  = plugin.getWorldManager().getGameWorld();
            boolean inSvoWorld = (lobby != null && player.getWorld().equals(lobby))
                              || (game  != null && player.getWorld().equals(game));
            if (inSvoWorld) {
                // В мире СВО - полная чистка и возврат в лобби (это реально осиротевший игрок СВО).
                plugin.getGameManager().stripSvoState(player, true);
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "Предыдущая игра СВО была прервана. Ты очищен и возвращён в лобби.");
            } else {
                // В ПОСТОРОННЕМ мире инвентарь НЕ трогаем - только снимаем осиротевшие SVO-теги.
                // (Тег мог навесить кто-то извне, напр. остатки командных блоков - не наказываем игрока.)
                plugin.getGameManager().stripSvoTagsOnly(player);
            }
        }

        // Игрок вышел в прошлой катке внутри игрового мира. Имя мира ("svogame_")
        // переиспользуется в новой катке, поэтому при заходе он окажется в МИРЕ
        // НОВОЙ игры, не будучи её участником. Если он не участник текущей игры,
        // но физически в игровом мире - чистим и возвращаем в лобби.
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                World gw = plugin.getWorldManager().getGameWorld();
                if (gw == null || !player.getWorld().equals(gw)) return;
                if (plugin.getGameManager().isParticipant(player)) return; // законный участник - не трогаем
                plugin.getGameManager().stripSvoState(player, true);
                player.sendMessage(org.bukkit.ChatColor.YELLOW + "Ты был возвращён в лобби (ты не участник текущей игры).");
            }
        }, 5L);
    }

    /**
     * Респаун для любого игрока связанного с СВО.
     *  ACTIVE + в игре: возле кровати ИЛИ рандом в spawn_box на Y=304
     *  QUEUE / has insvo: на стенд svorestart в gameWorld (или в лобби если нет gameWorld)
     *  В пустоте/вне диапазона: форс ТП на svorestart в лобби (никаких бесконечных смертей)
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        World gameWorld = plugin.getWorldManager().getGameWorld();
        World lobby     = plugin.getWorldManager().getLobbyWorld();

        // Уже выбывший наблюдатель (SPECTATOR) - оставляем его в игре, не кидаем в лобби.
        // Пилот FPV/Bombsender тоже наблюдатель, но он ещё в игре (у него остались жизни):
        // его НЕ ставим к живому игроку, а респавним как обычно (рандом в зоне высадки).
        if (player.getGameMode() == GameMode.SPECTATOR
                && !plugin.getGameManager().isPlayerInGame(player.getUniqueId())) {
            if (gameWorld != null && plugin.getGameManager().getState() == GameState.ACTIVE) {
                java.util.List<Player> live = plugin.getGameManager().getActivePlayers();
                Location to = !live.isEmpty() ? live.get(0).getLocation() : gameWorld.getSpawnLocation();
                event.setRespawnLocation(to);
            }
            return;
        }

        // Живой участник игры = в списке players и ещё inGame (не выбыл).
        // НЕ привязываемся к state==ACTIVE - это ломалось на гранях фаз/таймингах
        // и кидало живого игрока с 2 жизнями на стенд лобби вместо рандома в воздухе.
        boolean activeInGame  = gameWorld != null
                              && plugin.getGameManager().isPlayerInGame(player.getUniqueId());
        boolean isInQueue     = plugin.getGameManager().isInQueue(player);
        boolean hasInsvoTag   = player.getScoreboardTags().contains("insvo");

        Location respawnLoc = null;
        boolean usedBed = false;
        if (activeInGame && gameWorld != null) {
            // В игре - кровать ТОЛЬКО если игрок реально поспал в ЭТОЙ игре,
            // иначе рандом в spawn_box. (getRespawnLocation хранит кровать из
            // прошлых игр / клонированного мира - её игнорируем.)
            Location bedLoc = player.getRespawnLocation();
            if (plugin.getGameManager().hasSleptThisRound(player.getUniqueId())
                && bedLoc != null && gameWorld.equals(bedLoc.getWorld())
                && isSafeY(gameWorld, bedLoc.getY())) {
                respawnLoc = bedLoc;
                usedBed = true;
            } else {
                respawnLoc = plugin.getGameManager().getRandomSpawnLocation(gameWorld);
            }
        } else if ((isInQueue || hasInsvoTag) && gameWorld != null) {
            // В очереди - на стенд svorestart в gameWorld
            respawnLoc = plugin.getWorldManager().getRestartLocation(gameWorld);
        } else if (gameWorld != null && plugin.getGameManager().isGameRunning()
                   && plugin.getGameManager().hasSvoTags(player)) {
            // СТРАХОВКА: игра идёт, у игрока явно теги СВО - но по каким-то причинам
            // не попал ни в activeInGame, ни в очередь/insvo ветку (жалобы на респавн
            // в воздухе над точкой смерти были именно про этот случай - без этой ветки
            // он падал в дефолтный ванильный респавн у места смерти). Логируем, чтобы
            // при повторении было видно, что именно не совпало.
            plugin.getLogger().warning("[СВО] onRespawn: " + player.getName()
                + " не попал ни в одну ожидаемую ветку при идущей игре (activeInGame="
                + activeInGame + ", isInQueue=" + isInQueue + ", hasInsvoTag=" + hasInsvoTag
                + ") - принудительный рандом в spawn_box.");
            respawnLoc = plugin.getGameManager().getRandomSpawnLocation(gameWorld);
        } else {
            // Не в игре - проверяем дефолтный респавн
            Location defaultLoc = event.getRespawnLocation();
            if (defaultLoc == null
                || defaultLoc.getWorld() == null
                || !isSafeY(defaultLoc.getWorld(), defaultLoc.getY())) {
                // Дефолтный респавн опасный - кидаем в лобби
                if (lobby != null) {
                    respawnLoc = plugin.getWorldManager().getRestartLocation(lobby);
                }
            }
        }

        if (respawnLoc != null) {
            // Финальная защита от пустоты
            if (!isSafeY(respawnLoc.getWorld(), respawnLoc.getY())) {
                if (lobby != null) {
                    respawnLoc = plugin.getWorldManager().getRestartLocation(lobby);
                }
            }
            if (respawnLoc != null) event.setRespawnLocation(respawnLoc);
        }

        // Карта - всегда для тех кто в активной игре. Slow falling - только если НЕ с кровати.
        if (activeInGame) {
            final boolean giveSlowFalling = !usedBed;
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override public void run() {
                    Player p = Bukkit.getPlayer(player.getUniqueId());
                    if (p == null) return;
                    // Умер пилотом дрона (наблюдателем) - возвращаемся в игру живым.
                    if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(GameMode.SURVIVAL);
                    if (giveSlowFalling) {
                        p.addPotionEffect(new org.bukkit.potion.PotionEffect(
                            org.bukkit.potion.PotionEffectType.SLOW_FALLING, 800, 30, true, false));
                        plugin.getGameManager().markPendingDropSlowFall(p.getUniqueId());
                    }
                    dev.volansvo.svo.maps.MapData md = plugin.getMapManager().getActiveMap();
                    if (md != null && md.hasMapId()) {
                        plugin.getLootManager().giveMap(p, md.getMapId());
                    } else {
                        plugin.getLootManager().giveMap(p);
                    }
                }
            }, 2L);
        }
    }

    /**
     * Игрок успешно лёг в кровать в игровом мире во время игры -
     * запоминаем, что он "поспал в этой игре". Только тогда он
     * возродится у кровати (см. onRespawn).
     */
    @EventHandler
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player player = event.getPlayer();
        World gameWorld = plugin.getWorldManager().getGameWorld();
        if (gameWorld == null || !player.getWorld().equals(gameWorld)) return;
        if (plugin.getGameManager().getState() != GameState.ACTIVE) return;
        if (!plugin.getGameManager().isPlayerInGame(player.getUniqueId())) return;
        plugin.getGameManager().markSleptThisRound(player.getUniqueId());
    }

    /** Клик в GUI выбора команды. */
    @EventHandler
    public void onTeamGuiClick(InventoryClickEvent event) {
        if (event.getView() == null || event.getView().getTitle() == null) return;
        if (!event.getView().getTitle().startsWith(
                dev.volansvo.svo.managers.GameManager.TEAM_GUI_TITLE)) return;
        // Это наше меню - запрещаем забирать предметы.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        // Реагируем только на клики в верхнем (нашем) инвентаре.
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        Player p = (Player) event.getWhoClicked();
        plugin.getGameManager().handleTeamGuiClick(p, event.getRawSlot(), event.isRightClick(), event.isShiftClick());
    }

    /** Клик в GUI настройки /svoplay. */
    @EventHandler
    public void onSetupGuiClick(InventoryClickEvent event) {
        if (event.getView() == null || event.getView().getTitle() == null) return;
        if (!event.getView().getTitle().startsWith(
                dev.volansvo.svo.managers.GameManager.SETUP_GUI_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        Player p = (Player) event.getWhoClicked();
        plugin.getGameManager().handleSetupGuiClick(p, event.getRawSlot(), event.isRightClick());
    }

    /** Клик в GUI выбора цели ядерной кнопки. */
    @EventHandler
    public void onNukeGuiClick(InventoryClickEvent event) {
        if (event.getView() == null || event.getView().getTitle() == null) return;
        if (!event.getView().getTitle().startsWith(
                dev.volansvo.svo.managers.GameManager.NUKE_GUI_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        Player p = (Player) event.getWhoClicked();
        plugin.getGameManager().handleNukeGuiClick(p, event.getRawSlot());
    }

    /**
     * Спектейт-телепорт (клик по игроку в списке в режиме SPECTATOR) разрешён ТОЛЬКО
     * к игрокам, которые сейчас реально играют в СВО (живы, тег svoplayer, в игровом мире) -
     * белый список вместо чёрного. Раньше запрещалась только цель ВНЕ игрового мира СВО,
     * из-за чего зритель мог телепортироваться к кому угодно ВНУТРИ этого мира (другим
     * спектаторам, выбывшим, случайно забредшим) - и наоборот, если игровой мир пересоздавался
     * (клон карты), старая ссылка на World могла разойтись с текущей и телепорт молча блокировался
     * целиком. Теперь цель ищем напрямую среди активных участников - без зависимости от
     * идентичности объекта World у зрителя.
     */
    @EventHandler
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (!plugin.getGameManager().isGameRunning()) return;
        Player viewer = event.getPlayer();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) return;

        boolean validTarget = false;
        for (Player active : plugin.getGameManager().getActivePlayers()) {
            if (active.equals(viewer)) continue;
            if (!active.getWorld().equals(to.getWorld())) continue;
            if (active.getLocation().distanceSquared(to) < 4.0) { validTarget = true; break; }
        }
        if (!validTarget) {
            event.setCancelled(true);
            viewer.sendMessage(org.bukkit.ChatColor.RED
                + "Можно телепортироваться только к игрокам, которые сейчас играют в СВО.");
        }
    }

    /** Клик по предмету-«старт» (звезда) - запускает /svoplay от имени игрока. */
    @EventHandler
    public void onStartItemUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return; // только основная рука (без дабл-триггера)
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = event.getItem();
        if (!plugin.getGameManager().isStartItem(it)) return;
        event.setCancelled(true);
        event.getPlayer().performCommand("svoplay");
    }

    /** ПКМ по компасу - выход из очереди. */
    @EventHandler
    public void onLeaveQueueUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.getGameManager().isLeaveQueueItem(event.getItem())) return;
        event.setCancelled(true);
        plugin.getGameManager().leaveQueueByItem(event.getPlayer());
    }

    /** Эндер-сундук недоступен в игровом мире СВО (нельзя прятать/проносить вещи между мирами). */
    @EventHandler
    public void onEnderChestOpen(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        if (!(event.getPlayer() instanceof Player)) return;
        Player p = (Player) event.getPlayer();
        World gw = plugin.getWorldManager().getGameWorld();
        if (gw != null && p.getWorld().equals(gw)) {
            event.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Эндер-сундук недоступен в СВО.");
        }
    }

    /** ПКМ по барьеру - инициатор отменяет голосование. */
    @EventHandler
    public void onCancelVoteUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.getGameManager().isCancelVoteItem(event.getItem())) return;
        event.setCancelled(true);
        plugin.getGameManager().cancelVoteByItem(event.getPlayer());
    }

    /** ПКМ по ядерной кнопке - открыть GUI выбора цели. */
    @EventHandler
    public void onNukeButtonUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action a = event.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = event.getItem();
        if (!plugin.getGameManager().isNukeButton(it)) return;
        event.setCancelled(true); // не даём поставить TNT
        if (plugin.getGameManager().getState() != GameState.ACTIVE) return;
        plugin.getGameManager().openNukeGui(event.getPlayer());
    }

    /** Подобрал ядерную кнопку с земли - становится её владельцем. */
    @EventHandler
    public void onNukePickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (!plugin.getGameManager().isNukeButton(event.getItem().getItemStack())) return;
        Player p = (Player) event.getEntity();
        plugin.getWardenManager().onNukePickup(p.getUniqueId());
        p.sendMessage(ChatColor.GOLD + "Ты подобрал " + ChatColor.RED + "☢ ЯДЕРНУЮ КНОПКУ ☢"
            + ChatColor.GOLD + "! ПКМ чтобы выбрать цель и запустить.");
    }

    /** Выброс предметов: «старт» выбрасывать нельзя; брошенная кнопка - снимает владельца. */
    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        ItemStack ds = event.getItemDrop().getItemStack();
        if (plugin.getGameManager().isStartItem(ds) || plugin.getGameManager().isCancelVoteItem(ds)
                || plugin.getGameManager().isLeaveQueueItem(ds)) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getGameManager().isNukeButton(ds)) {
            plugin.getWardenManager().onNukeDropped(event.getPlayer().getUniqueId());
        }
    }

    /**
     * Смена мира: вход в svogame_ до игры - выдаём предмет-«старт».
     * Выход ВЛАДЕЛЬЦА ядерной кнопки из игрового мира - кнопка достаётся всем живым.
     */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        final Player player = event.getPlayer();
        final java.util.UUID uid = player.getUniqueId();
        World gw = plugin.getWorldManager().getGameWorld();
        boolean active = plugin.getGameManager().isGameActive();

        // Игрок в очереди покинул мир, где стоит стенд-точка возврата /svolobby (standtpspawn2) -
        // например, ушёл порталом в посторонний мир. Предметы очереди там больше не нужны и не
        // сработают - снимаем их и автоматически выводим из очереди.
        if (plugin.getGameManager().isInQueue(player)) {
            Location standLoc = plugin.getGameManager().getLobbyReturnLocation();
            World standWorld = standLoc != null ? standLoc.getWorld() : null;
            if (standWorld != null && event.getFrom().equals(standWorld) && !player.getWorld().equals(standWorld)) {
                plugin.getGameManager().leaveQueueOnWorldExit(player);
            }
        }

        if (gw != null && event.getFrom().equals(gw)) {
            plugin.getWardenManager().onNukeOwnerLeft(uid);
            // Участник покинул игровой мир во время игры = честная смерть (нельзя сбежать ТП).
            if (active && plugin.getGameManager().isPlayerInGame(uid)) {
                plugin.getGameManager().handleArenaLeave(uid);
            }
        }

        // Посторонний (не участник и не зритель) попал в игровой мир во время игры - выкидываем.
        if (gw != null && player.getWorld().equals(gw) && active
                && !plugin.getGameManager().isPlayerInGame(uid)
                && player.getGameMode() != GameMode.SPECTATOR) {
            World lobby = plugin.getWorldManager().getLobbyWorld();
            if (lobby != null) {
                Location loc = plugin.getWorldManager().getRestartLocation(lobby);
                player.teleport(loc != null ? loc : lobby.getSpawnLocation());
                player.sendMessage(org.bukkit.ChatColor.RED + "Во время игры нельзя находиться в мире СВО.");
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override public void run() {
                if (!player.isOnline()) return;
                plugin.getGameManager().giveStartItem(player);
            }
        }, 5L);
    }

    private boolean isSafeY(World w, double y) {
        if (w == null) return false;
        return y >= w.getMinHeight() + 5 && y <= w.getMaxHeight() - 5;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        final java.util.UUID uid = player.getUniqueId();
        // Ботов убирает сама игра при завершении - это не выход игрока.
        if (plugin.getBotManager().isRemovingAll()) return;
        // ВАЖНО: снимаем состояние ДО removeFromQueue - иначе isInQueue ниже всегда false
        // (раньше порядок был неправильный, ветка с очередью была мёртвой).
        boolean wasInGame  = plugin.getGameManager().isPlayerInGame(uid);
        boolean wasInQueue = plugin.getGameManager().isInQueue(player);
        boolean hadSvoTags = plugin.getGameManager().hasSvoTags(player);

        plugin.getGameManager().removeFromQueue(player);
        plugin.getStatsManager().saveAll();

        if (wasInGame) {
            // Combat-log: выход во время игры = честная смерть - дропаем лут на месте,
            // засчитываем килл последнему дамагеру, затем полное выбывание.
            plugin.getGameManager().handleCombatLog(player);
            // forceEliminate САМ снимает командную принадлежность (svoteam=0).
            plugin.getGameManager().forceEliminate(uid);
        } else if (wasInQueue || hadSvoTags) {
            // Снимаем командную принадлежность ТОЛЬКО у реальных участников СВО.
            plugin.getGameManager().clearTeamMembershipOnQuit(uid);
        }
        // Если этим выходом мир опустел - аварийно останавливаем и чистим игру.
        plugin.getGameManager().checkAbandoned();
    }

}
