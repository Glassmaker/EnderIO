package crazypants.enderio.machine.monitor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import crazypants.enderio.Log;
import crazypants.enderio.network.IPacketEio;
import crazypants.util.DyeColor;

public class PacketPowerMonitor implements IPacketEio {

  int x;
  int y;
  int z;
  boolean engineControlEnabled;
  float startLevel;
  float stopLevel;
  DyeColor signalColor;

  public PacketPowerMonitor() {
  }

  public PacketPowerMonitor(TilePowerMonitor pm) {
    x = pm.xCoord;
    y = pm.yCoord;
    z = pm.zCoord;
  }

  @Override
  public void encode(ChannelHandlerContext ctx, ByteBuf buf) {
    buf.writeInt(x);
    buf.writeInt(y);
    buf.writeInt(z);
    buf.writeBoolean(engineControlEnabled);
    buf.writeFloat(startLevel);
    buf.writeFloat(stopLevel);
    buf.writeShort(signalColor.ordinal());
  }

  @Override
  public void decode(ChannelHandlerContext ctx, ByteBuf buffer) {
    x = buffer.readInt();
    y = buffer.readInt();
    z = buffer.readInt();
    engineControlEnabled = buffer.readBoolean();
    startLevel = buffer.readFloat();
    stopLevel = buffer.readFloat();
    signalColor = DyeColor.fromIndex(buffer.readShort());
  }

  @Override
  public void handleClientSide(EntityPlayer player) {
    handle(player);
  }

  @Override
  public void handleServerSide(EntityPlayer player) {
    handle(player);
  }

  public void handle(EntityPlayer player) {
    TileEntity te = player.worldObj.getTileEntity(x, y, z);
    if(!(te instanceof TilePowerMonitor)) {
      Log.warn("createPowerMonitotPacket: Could not handle packet as TileEntity was not a TilePowerMonitor.");
      return;
    }
    TilePowerMonitor pm = (TilePowerMonitor) te;
    pm.engineControlEnabled = engineControlEnabled;
    pm.startLevel = startLevel;
    pm.stopLevel = stopLevel;
    pm.signalColor = signalColor;
    player.worldObj.markBlockForUpdate(x, y, z);
  }

}