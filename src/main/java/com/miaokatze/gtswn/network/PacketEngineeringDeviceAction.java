package com.miaokatze.gtswn.network;

import java.util.UUID;

import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Fixed-size factory intent. The server resolves the selected provider again after queueing. */
public final class PacketEngineeringDeviceAction implements IMessage {

    public static final byte SCAN = 0;
    public static final byte TOGGLE_VISUALIZATION = 1;
    byte action = -1;
    SourceOrigin sourceOrigin = SourceOrigin.NONE;
    UUID terminalId;

    public PacketEngineeringDeviceAction() {}

    public PacketEngineeringDeviceAction(byte action, Source source) {
        this.action = action;
        sourceOrigin = source.origin;
        try {
            terminalId = UUID.fromString(source.binding);
        } catch (IllegalArgumentException invalid) {
            terminalId = null;
        }
    }

    public boolean valid() {
        return (action == SCAN || action == TOGGLE_VISUALIZATION)
            && (sourceOrigin == SourceOrigin.BAUBLE || sourceOrigin == SourceOrigin.INVENTORY)
            && terminalId != null
            && !PacketDeviceTerminalVisualAction.NEW_TERMINAL.equals(terminalId);
    }

    public boolean matches(Source actual) {
        return valid() && actual != null
            && actual.present()
            && sourceOrigin == actual.origin
            && terminalId.toString()
                .equals(actual.binding);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        UUID id = terminalId == null ? PacketDeviceTerminalVisualAction.NEW_TERMINAL : terminalId;
        buffer.writeByte(action);
        buffer.writeByte(sourceOrigin.ordinal());
        buffer.writeLong(id.getMostSignificantBits());
        buffer.writeLong(id.getLeastSignificantBits());
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        action = -1;
        sourceOrigin = SourceOrigin.NONE;
        terminalId = null;
        if (buffer.readableBytes() != 18) return;
        byte decodedAction = buffer.readByte();
        int origin = buffer.readUnsignedByte();
        UUID id = new UUID(buffer.readLong(), buffer.readLong());
        if (origin >= SourceOrigin.values().length) return;
        action = decodedAction;
        sourceOrigin = SourceOrigin.values()[origin];
        terminalId = id;
        if (!valid()) action = -1;
    }

    public static final class Handler implements IMessageHandler<PacketEngineeringDeviceAction, IMessage> {

        @Override
        public IMessage onMessage(PacketEngineeringDeviceAction message, MessageContext context) {
            if (!context.side.isServer() || !message.valid()) return null;
            if (PerformanceAudit.enabled()) PerformanceAudit.recordPacketReceived(35);
            DeviceTerminalVisualActionQueue.enqueue(context.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
