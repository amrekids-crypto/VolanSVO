package dev.volansvo.svo.bots.nms;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Серверный игрок без клиента.
 *
 * У настоящего игрока физику считает клиент, а сервер только принимает пакеты движения.
 * Здесь клиента нет, поэтому сервер сам прогоняет ванильный шаг игрока (doTick: aiStep,
 * travel, падение, вода, подбор предметов, голод) с вводом от ИИ: вперёд/вбок/прыжок.
 * Так бот подчиняется той же физике, что и живой игрок: не летает, падает, тонет,
 * получает урон от падения и отдачу.
 */
public final class BotPlayer extends ServerPlayer {

    /** Ввод ИИ на текущий тик. Как клавиши W/S и A/D: от -1 до 1. */
    public float inputForward;
    public float inputStrafe;
    public boolean inputJump;

    public BotPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
        super(server, level, profile, info);
    }

    @Override
    public void tick() {
        // Клиент шлёт движение каждый тик, и сервер по нему двигает загрузку чанков.
        // У бота пакетов нет, поэтому раз в полсекунды делаем это сами.
        if (this.tickCount % 10 == 0 && this.connection != null) {
            this.connection.resetPosition();
            this.serverLevel().getChunkSource().move(this);
        }
        // После телепорта в другой мир сервер ждёт от клиента подтверждения и до тех пор
        // не даёт игроку получать урон. Подтверждать некому - снимаем флаг сами.
        if (this.isChangingDimension()) this.hasChangedDimension();
        acceptTeleport();
        super.tick();
        if (this.isRemoved() || this.connection == null) return;
        if (!this.isDeadOrDying()) {
            this.zza = this.inputForward;
            this.xxa = this.inputStrafe;
            this.setJumping(this.inputJump);
        } else {
            this.zza = 0f; this.xxa = 0f; this.setJumping(false);
        }
        this.doTick();
    }

    private static java.lang.reflect.Field awaitingPos, awaitingId;

    /**
     * После телепорта сервер ждёт, пока клиент подтвердит новую позицию, и до тех пор
     * не принимает клики по блокам (ставить блоки и технику) и движение. Подтверждаем сами,
     * как это сделал бы клиент.
     */
    private void acceptTeleport() {
        if (this.connection == null) return;
        try {
            if (awaitingPos == null) {
                Class<?> c = net.minecraft.server.network.ServerGamePacketListenerImpl.class;
                java.lang.reflect.Field pos = c.getDeclaredField("awaitingPositionFromClient");
                java.lang.reflect.Field id = c.getDeclaredField("awaitingTeleport");
                pos.setAccessible(true);
                id.setAccessible(true);
                awaitingId = id;
                awaitingPos = pos;
            }
            if (awaitingPos.get(this.connection) == null) return;
            int id = awaitingId.getInt(this.connection);
            this.connection.handleAcceptTeleportPacket(
                new net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket(id));
        } catch (Throwable ignored) {
        }
    }

    /** После смены мира неуязвимость держится до ответа клиента, а у бота его не будет. */
    @Override
    public boolean isInvulnerableTo(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
        if (this.isChangingDimension()) this.hasChangedDimension();
        return super.isInvulnerableTo(level, source);
    }

    /** Клиента нет, ждать подтверждения загрузки мира (после смены мира/респавна) не от кого. */
    @Override
    public boolean hasClientLoaded() {
        return true;
    }

    /**
     * Урон от падения у игрока сервер считает по пакетам движения клиента, а Entity.move
     * для игроков его пропускает. Бот двигается без пакетов, поэтому падал без урона и
     * спокойно шагал с крыш. Считаем так же, как сервер считает по пакету клиента.
     */
    @Override
    public void move(net.minecraft.world.entity.MoverType type, net.minecraft.world.phys.Vec3 movement) {
        double x0 = this.getX(), y0 = this.getY(), z0 = this.getZ();
        super.move(type, movement);
        if (type == net.minecraft.world.entity.MoverType.SELF && !this.isSpectator() && !this.isPassenger()) {
            this.doCheckFallDamage(this.getX() - x0, this.getY() - y0, this.getZ() - z0, this.onGround());
        }
    }

    /** Движение бота считает сервер, поэтому он и есть «локальный» владелец сущности. */
    @Override
    public boolean isControlledByLocalInstance() {
        return true;
    }
}
