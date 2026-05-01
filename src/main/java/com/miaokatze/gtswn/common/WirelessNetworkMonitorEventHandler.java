// package com.miaokatze.gtswn.common;
//
// import java.math.BigInteger;
// import java.util.UUID;
//
// import net.minecraft.client.Minecraft;
// import net.minecraft.entity.player.EntityPlayer;
// import net.minecraft.util.ChatComponentText;
// import net.minecraftforge.event.world.WorldEvent;
//
// import cpw.mods.fml.common.eventhandler.SubscribeEvent;
// import cpw.mods.fml.common.gameevent.TickEvent;
// import cpw.mods.fml.relauncher.Side;
// import cpw.mods.fml.relauncher.SideOnly;
// import gregtech.common.misc.WirelessNetworkManager;
//
/// **
// * 无线网络监测事件处理器
// * 每tick监测无线电网容量，变化时输出聊天
// */
// public class WirelessNetworkMonitorEventHandler {
//
// // 记录上一次的电网能量值
// private static BigInteger lastNetworkEnergy = null;
//
// @SubscribeEvent
// @SideOnly(Side.CLIENT)
// public void onClientTick(TickEvent.ClientTickEvent event) {
// if (event.phase != TickEvent.Phase.END) {
// return;
// }
// if (Minecraft.getMinecraft().thePlayer == null) {
// return;
// }
// if (Minecraft.getMinecraft().theWorld == null) {
// return;
// }
//
// // 获取当前玩家UUID
// EntityPlayer player = Minecraft.getMinecraft().thePlayer;
// UUID ownerUUID = player.getUniqueID();
//
// // 从WirelessNetworkManager读取能量
// BigInteger currentNetworkEnergy = WirelessNetworkManager.getUserEU(ownerUUID);
//
// // 第一次记录
// if (lastNetworkEnergy == null) {
// lastNetworkEnergy = currentNetworkEnergy;
// player.addChatMessage(new ChatComponentText("§6[无线网络监测] 初始能量: " + currentNetworkEnergy + " EU"));
// return;
// }
//
// // 检查是否有变化
// if (!currentNetworkEnergy.equals(lastNetworkEnergy)) {
// BigInteger difference = currentNetworkEnergy.subtract(lastNetworkEnergy);
// String differenceText;
// if (difference.signum() > 0) {
// differenceText = "§a+" + difference + " EU";
// } else if (difference.signum() < 0) {
// differenceText = "§c" + difference + " EU";
// } else {
// differenceText = "§7" + difference + " EU";
// }
//
// // 输出变化
// player.addChatMessage(
// new ChatComponentText("§6[无线网络监测] 当前能量: " + currentNetworkEnergy + " EU (" + differenceText + "§6)"));
//
// // 更新上一次的
// lastNetworkEnergy = currentNetworkEnergy;
// }
// }
//
// @SubscribeEvent
// public void onWorldLoad(WorldEvent.Load event) {
// // 世界加载时重置上次的值
// lastNetworkEnergy = null;
// }
// }
