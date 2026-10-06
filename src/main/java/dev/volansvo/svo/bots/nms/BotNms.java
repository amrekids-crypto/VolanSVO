package dev.volansvo.svo.bots.nms;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Input;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Весь доступ к внутренностям сервера для ботов собран здесь.
 *
 * Действия бота идут через те же обработчики пакетов, что и у живого игрока
 * (ПКМ, удар, смена слота, присед, спринт). Поэтому другие плагины (MilitaryCraft,
 * ExecutableItems и т.п.) видят обычные PlayerInteractEvent/PlayerItemHeldEvent/
 * EntityDamageByEntityEvent и работают с ботом как с игроком.
 */
public final class BotNms {

    private BotNms() {}

    /** Счётчик подтверждений для пакетов использования предмета. */
    private static int sequence = 1;

    // ---------------------------------------------------------------- жизненный цикл

    /** Создаёт бота и вводит его на сервер как обычного игрока (PlayerJoinEvent, таб, трекинг). */
    public static Player spawn(String name, UUID uuid, Location loc, String skinValue, String skinSignature) {
        MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
        ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();

        GameProfile profile = new GameProfile(uuid, name);
        if (skinValue != null && !skinValue.isEmpty()) {
            profile.getProperties().put("textures", new Property("textures", skinValue, skinSignature));
        }
        // Дальность прорисовки 4: сервер грузит вокруг бота меньше чанков, чем вокруг живого
        // игрока. 0x7F - все слои скина (куртка, рукава, штанины, шляпа).
        ClientInformation info = new ClientInformation("ru_ru", 4, ChatVisiblity.FULL, true, 0x7F,
            HumanoidArm.RIGHT, false, true, ParticleStatus.MINIMAL);

        BotPlayer bot = new BotPlayer(server, level, profile, info);
        FakeConnection connection = new FakeConnection();
        server.getPlayerList().placeNewPlayer(connection, bot, new CommonListenerCookie(profile, 0, info, false));
        bot.setClientLoaded(true);

        Player bukkit = bot.getBukkitEntity();
        bukkit.teleport(loc);
        return bukkit;
    }

    /** Убирает бота с сервера тем же путём, что и выход игрока (PlayerQuitEvent, сохранение). */
    public static void remove(Player p) {
        BotPlayer bot = handle(p);
        if (bot == null || bot.connection == null) return;
        bot.connection.onDisconnect(new DisconnectionDetails(Component.literal("bot removed")));
    }

    public static boolean isBot(Player p) {
        return handle(p) != null;
    }

    public static BotPlayer handle(Player p) {
        if (!(p instanceof CraftPlayer)) return null;
        Object h = ((CraftPlayer) p).getHandle();
        return (h instanceof BotPlayer) ? (BotPlayer) h : null;
    }

    // ---------------------------------------------------------------- движение

    public static void input(Player p, float forward, float strafe, boolean jump) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        bot.inputForward = clamp(forward);
        bot.inputStrafe = clamp(strafe);
        bot.inputJump = jump;
    }

    public static void look(Player p, float yaw, float pitch) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        bot.setYRot(yaw);
        bot.setXRot(Math.max(-90f, Math.min(90f, pitch)));
        bot.setYHeadRot(yaw);
    }

    public static boolean onGround(Player p) {
        BotPlayer bot = handle(p);
        return bot != null && bot.onGround();
    }

    public static boolean horizontalCollision(Player p) {
        BotPlayer bot = handle(p);
        return bot != null && bot.horizontalCollision;
    }

    public static boolean inWater(Player p) {
        BotPlayer bot = handle(p);
        return bot != null && (bot.isInWater() || bot.isInLava());
    }

    // ---------------------------------------------------------------- действия как у клиента

    /** ПКМ в воздух предметом из руки (выстрел, бросок, поедание, натяжение лука...). */
    public static void useItem(Player p, boolean offhand) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        ServerboundUseItemPacket packet = new ServerboundUseItemPacket(
            offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
            sequence++, bot.getYRot(), bot.getXRot());
        // Антиспам Paper считает пакеты по этому времени. Его ставит только декодер сетевого
        // пакета; у созданного в коде там 0, и после 8 использований все следующие ПКМ
        // бота отбрасывались бы навсегда (еда, аптечки, выстрелы).
        packet.timestamp = System.currentTimeMillis();
        bot.connection.handleUseItem(packet);
    }

    /**
     * ПКМ по грани блока (x,y,z) с предметом в руке: ставит блок рядом с этой гранью так же,
     * как клиент (BlockPlaceEvent, проверка дальности, расход предмета).
     * @param face грань: 0 низ, 1 верх, 2 север, 3 юг, 4 запад, 5 восток
     */
    public static void useItemOn(Player p, int x, int y, int z, int face) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        Direction dir = Direction.from3DDataValue(face);
        Vec3 hit = new Vec3(x + 0.5 + dir.getStepX() * 0.5, y + 0.5 + dir.getStepY() * 0.5, z + 0.5 + dir.getStepZ() * 0.5);
        ServerboundUseItemOnPacket packet = new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, dir, new BlockPos(x, y, z), false), sequence++);
        packet.timestamp = System.currentTimeMillis(); // см. useItem: антиспам Paper
        bot.connection.handleUseItemOn(packet);
    }

    /** Поставить бота в точку (полёт дроном в режиме наблюдателя, где физики нет). */
    public static void moveTo(Player p, double x, double y, double z, float yaw, float pitch) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        bot.absMoveTo(x, y, z, yaw, pitch);
        bot.setYHeadRot(yaw);
        bot.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }

    /**
     * Нажать «прыжок» (с зажатым приседом, если shift): как клиент шлёт ввод. Плюс событие
     * прыжка Paper - плагины ловят прыжок по одному из них (в полёте наблюдателем обычного прыжка нет).
     */
    public static void pressJump(Player p, boolean shift) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        bot.connection.handlePlayerInput(new ServerboundPlayerInputPacket(
            new Input(false, false, false, false, true, shift, bot.isSprinting())));
        bot.connection.handlePlayerInput(new ServerboundPlayerInputPacket(
            new Input(false, false, false, false, false, shift, bot.isSprinting())));
        org.bukkit.Location from = p.getLocation();
        new com.destroystokyo.paper.event.player.PlayerJumpEvent(p, from, from.clone().add(0, 0.42, 0)).callEvent();
    }

    /** Отпустить ПКМ (выстрел из лука, прекращение еды/щита). */
    public static void releaseUseItem(Player p) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        bot.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
    }

    /** Удар по сущности (ЛКМ по цели). Ванильный урон, крит, откидывание, события. */
    public static void attack(Player p, Entity target) {
        BotPlayer bot = handle(p);
        if (bot == null || target == null) return;
        net.minecraft.world.entity.Entity nms = ((CraftEntity) target).getHandle();
        bot.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(nms, bot.isShiftKeyDown()));
        swing(p);
    }

    /** Взмах рукой. Если ни во что не попал - для плагинов это LEFT_CLICK_AIR. */
    public static void swing(Player p) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        bot.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    public static void selectSlot(Player p, int slot) {
        BotPlayer bot = handle(p);
        if (bot == null || slot < 0 || slot > 8) return;
        if (bot.getInventory().selected == slot) return;
        bot.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
    }

    public static void sneak(Player p, boolean on) {
        BotPlayer bot = handle(p);
        if (bot == null || bot.isShiftKeyDown() == on) return;
        // В 1.21.4 присед (и PlayerToggleSneakEvent) включает команда, пакет ввода лишь дублирует.
        bot.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(bot,
            on ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        bot.connection.handlePlayerInput(new ServerboundPlayerInputPacket(
            new Input(bot.inputForward > 0, bot.inputForward < 0, bot.inputStrafe > 0, bot.inputStrafe < 0,
                bot.inputJump, on, bot.isSprinting())));
    }

    public static void sprint(Player p, boolean on) {
        BotPlayer bot = handle(p);
        if (bot == null || bot.isSprinting() == on) return;
        bot.connection.handlePlayerCommand(new ServerboundPlayerCommandPacket(bot,
            on ? ServerboundPlayerCommandPacket.Action.START_SPRINTING
               : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
    }

    /**
     * Клавиши W/S/A/D, прыжок и присед пакетом ввода, как их шлёт клиент. По нему едет
     * техника (Player.getCurrentInput) и по приседу пассажир слезает с сиденья.
     */
    public static void keys(Player p, boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean shift) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        Input in = new Input(forward, back, left, right, jump, shift, bot.isSprinting());
        if (in.equals(bot.getLastClientInput())) return;
        bot.connection.handlePlayerInput(new ServerboundPlayerInputPacket(in));
    }

    /** ПКМ по сущности (сесть в технику, открыть, взаимодействовать). */
    public static void interact(Player p, Entity target) {
        BotPlayer bot = handle(p);
        if (bot == null || target == null) return;
        net.minecraft.world.entity.Entity nms = ((CraftEntity) target).getHandle();
        bot.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(nms, bot.isShiftKeyDown(), InteractionHand.MAIN_HAND));
    }

    public static void swapHands(Player p) {
        BotPlayer bot = handle(p);
        if (bot == null) return;
        bot.connection.handlePlayerAction(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
