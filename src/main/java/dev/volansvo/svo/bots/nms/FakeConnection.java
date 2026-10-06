package dev.volansvo.svo.bots.nms;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;

/**
 * Соединение без клиента. Сервер считает его живым, все исходящие пакеты молча
 * выбрасываются. Конфигурация протокола (pipeline netty) пропускается целиком:
 * у бота нет кодеков, ему нечего кодировать.
 */
public final class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        EmbeddedChannel ch = new EmbeddedChannel();
        // Те же имена обработчиков, что у настоящего соединения: ProtocolLib и подобные
        // плагины вставляют свои обработчики рядом с "encoder"/"decoder" и падали без них.
        ChannelPipeline pl = ch.pipeline();
        pl.addLast("splitter", new ChannelInboundHandlerAdapter());
        pl.addLast("decoder", new ChannelInboundHandlerAdapter());
        pl.addLast("prepender", new Sink());
        pl.addLast("encoder", new Sink());
        pl.addLast("packet_handler", new ChannelInboundHandlerAdapter());
        this.channel = ch;
        this.address = new InetSocketAddress("127.0.0.1", 0);
    }

    /** Всё, что кто-то пишет в канал бота, выбрасывается (клиента нет, копить нельзя). */
    private static final class Sink extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {}
    }

    @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}
    @Override public void setupOutboundProtocol(ProtocolInfo<?> info) {}
    @Override public void setListenerForServerboundHandshake(PacketListener listener) {}

    @Override public void send(Packet<?> packet) {}
    @Override public void send(Packet<?> packet, PacketSendListener listener) {}
    @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
    @Override public void flushChannel() {}

    @Override public boolean isConnected() { return true; }
    @Override public boolean isConnecting() { return false; }
    @Override public boolean isMemoryConnection() { return false; }

    @Override public void setReadOnly() {}
    @Override public void enableAutoRead() {}
    @Override public void handleDisconnection() {}
    @Override public void disconnect(Component reason) {}
    @Override public void disconnect(DisconnectionDetails details) {}
}
