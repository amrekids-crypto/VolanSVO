package dev.volansvo.svo.bots;

import dev.volansvo.svo.VolanSVO;
import dev.volansvo.svo.bots.nms.BotNms;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Подсветка тиммейтов сквозь стены, видимая только своей команде.
 *
 * Свечение - это бит 0x40 в «общих флагах» сущности. Сервер шлёт его всем одинаково,
 * поэтому в канал каждого игрока встроен перехватчик: в пакетах данных тиммейтов этого
 * игрока бит выставляется, остальным игрокам пакеты уходят как есть. Тиммейты остаются
 * подсвеченными и для выбывшего игрока (он наблюдает за своими).
 */
public final class TeamGlow implements Listener {

    private static final String HANDLER = "volansvo_team_glow";
    private static final byte GLOW = 0x40;

    private final VolanSVO plugin;
    private final BotManager bots;
    private final VolanHooks hooks;
    /** зритель -> id сущностей его тиммейтов (читается из потока сети). */
    private final Map<UUID, Set<Integer>> glowFor = new ConcurrentHashMap<UUID, Set<Integer>>();
    /** Команда игрока на эту игру (выбывшему команду не сбрасываем). */
    private final Map<UUID, Integer> teamOf = new HashMap<UUID, Integer>();
    private static EntityDataAccessor<Byte> flagsAccessor;

    TeamGlow(VolanSVO plugin, BotManager bots, VolanHooks hooks) {
        this.plugin = plugin;
        this.bots = bots;
        this.hooks = hooks;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) install(p);
        new BukkitRunnable() { @Override public void run() { refresh(); } }.runTaskTimer(plugin, 20L, 20L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        install(e.getPlayer());
    }

    private void install(Player p) {
        if (BotNms.isBot(p)) return;
        try {
            Channel ch = ((CraftPlayer) p).getHandle().connection.connection.channel;
            if (ch == null || ch.pipeline().get(HANDLER) != null || ch.pipeline().get("packet_handler") == null) return;
            final UUID viewer = p.getUniqueId();
            ch.pipeline().addBefore("packet_handler", HANDLER, new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    Set<Integer> ids = glowFor.get(viewer);
                    if (ids != null && !ids.isEmpty() && msg instanceof Packet) msg = transform((Packet<?>) msg, ids);
                    super.write(ctx, msg, promise);
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("[Подсветка] не удалось подключиться к " + p.getName() + ": " + t);
        }
    }

    // =====================================================================  поток сети

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object transform(Packet<?> pk, Set<Integer> ids) {
        if (pk instanceof ClientboundSetEntityDataPacket) {
            ClientboundSetEntityDataPacket d = (ClientboundSetEntityDataPacket) pk;
            if (!ids.contains(d.id())) return pk;
            List<SynchedEntityData.DataValue<?>> out = null;
            List<SynchedEntityData.DataValue<?>> in = d.packedItems();
            for (int i = 0; i < in.size(); i++) {
                SynchedEntityData.DataValue<?> v = in.get(i);
                if (v.id() == 0 && v.value() instanceof Byte && (((Byte) v.value()) & GLOW) == 0) {
                    if (out == null) out = new ArrayList<SynchedEntityData.DataValue<?>>(in);
                    out.set(i, new SynchedEntityData.DataValue(0, (EntityDataSerializer) v.serializer(), (byte) (((Byte) v.value()) | GLOW)));
                }
            }
            return out == null ? pk : new ClientboundSetEntityDataPacket(d.id(), out);
        }
        if (pk instanceof ClientboundBundlePacket) {
            List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> list = new ArrayList();
            boolean changed = false;
            for (Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> sub : ((ClientboundBundlePacket) pk).subPackets()) {
                Object t = transform(sub, ids);
                if (t != sub) changed = true;
                list.add((Packet) t);
            }
            return changed ? new ClientboundBundlePacket(list) : pk;
        }
        return pk;
    }

    // =====================================================================  главный поток

    /** Раз в секунду: кто чьи тиммейты; новым - разослать флаги, ушедшим - убрать свечение. */
    private void refresh() {
        boolean running = hooks.gameActive();
        if (!running) {
            if (!glowFor.isEmpty()) {
                Map<UUID, Set<Integer>> old = new HashMap<UUID, Set<Integer>>(glowFor);
                glowFor.clear();
                for (Map.Entry<UUID, Set<Integer>> en : old.entrySet()) resend(en.getKey(), en.getValue());
            }
            teamOf.clear();
            return;
        }
        // Запоминаем команды (у выбывшего потом teamId может сброситься).
        for (Player p : Bukkit.getOnlinePlayers()) {
            int t = hooks.teamIdOf(p.getUniqueId());
            if (t >= 0) teamOf.put(p.getUniqueId(), t);
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (BotNms.isBot(viewer)) continue;
            Integer team = teamOf.get(viewer.getUniqueId());
            Set<Integer> now = new HashSet<Integer>();
            if (team != null) {
                for (Player o : viewer.getWorld().getPlayers()) {
                    if (o.equals(viewer) || !team.equals(teamOf.get(o.getUniqueId()))) continue;
                    if (!hooks.inGame(o.getUniqueId())) continue;
                    now.add(o.getEntityId());
                }
            }
            Set<Integer> before = glowFor.get(viewer.getUniqueId());
            if (now.isEmpty()) glowFor.remove(viewer.getUniqueId());
            else glowFor.put(viewer.getUniqueId(), Collections.unmodifiableSet(now));
            Set<Integer> changed = new HashSet<Integer>(now);
            if (before != null) { changed.removeAll(before); Set<Integer> gone = new HashSet<Integer>(before); gone.removeAll(now); changed.addAll(gone); }
            // Раз в 5 секунд - всем подряд (на случай, если клиент что-то пропустил).
            if (bots.now() % 100 < 20) changed.addAll(now);
            if (!changed.isEmpty()) resend(viewer.getUniqueId(), changed);
        }
    }

    /** Отправить зрителю текущие флаги этих сущностей (перехватчик добавит свечение). */
    private void resend(UUID viewerId, Set<Integer> ids) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null) return;
        EntityDataAccessor<Byte> acc = accessor();
        if (acc == null) return;
        ServerPlayer sp = ((CraftPlayer) viewer).getHandle();
        for (Player o : viewer.getWorld().getPlayers()) {
            if (!ids.contains(o.getEntityId())) continue;
            net.minecraft.world.entity.Entity nms = ((CraftEntity) o).getHandle();
            Byte flags = nms.getEntityData().get(acc);
            List<SynchedEntityData.DataValue<?>> vals = new ArrayList<SynchedEntityData.DataValue<?>>();
            vals.add(SynchedEntityData.DataValue.create(acc, flags));
            sp.connection.send(new ClientboundSetEntityDataPacket(o.getEntityId(), vals));
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Byte> accessor() {
        if (flagsAccessor != null) return flagsAccessor;
        try {
            Field f = net.minecraft.world.entity.Entity.class.getDeclaredField("DATA_SHARED_FLAGS_ID");
            f.setAccessible(true);
            flagsAccessor = (EntityDataAccessor<Byte>) f.get(null);
        } catch (Throwable ignored) {}
        return flagsAccessor;
    }
}
