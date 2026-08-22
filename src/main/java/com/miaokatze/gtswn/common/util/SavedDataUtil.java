package com.miaokatze.gtswn.common.util;

import java.util.function.Function;

import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;

/**
 * WorldSavedData 加载/挂载模板（O2-15，原 SWN-OPT-09 方案 Q2-1）。
 * <p>
 * {@code QuantumControllerRegistry} / {@code NetworkInfoDataStore} / {@code AEMonitorDataStore}
 * 三份 {@code get(World)} 此前为逐字符相同的模板（仅类型与 DATA_NAME 不同，Registry 另有
 * dimensionId 赋值一行）——收敛为本工具单点。挂载目标均为 {@code world.perWorldStorage}
 * （每维度独立落盘），行为与原模板一致：不存在则构造实例并 setData 挂载。
 */
public final class SavedDataUtil {

    private SavedDataUtil() {}

    /**
     * 从世界的 perWorldStorage 取或创建 WorldSavedData。
     *
     * @param world   目标世界
     * @param name    WorldSavedData 注册名（mapName / 落盘文件名）
     * @param type    数据类（loadData 按类过滤，防跨类型同名串档）
     * @param factory 不存在时的构造器（入参为注册名）
     * @return 已加载或新建并挂载的数据实例
     */
    public static <T extends WorldSavedData> T loadOrCreate(World world, String name, Class<T> type,
        Function<String, T> factory) {
        MapStorage storage = world.perWorldStorage;
        T data = type.cast(storage.loadData(type, name));
        if (data == null) {
            data = factory.apply(name);
            storage.setData(name, data);
        }
        return data;
    }
}
