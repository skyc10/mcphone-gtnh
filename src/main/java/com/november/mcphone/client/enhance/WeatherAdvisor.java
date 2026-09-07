package com.november.mcphone.client.enhance;

import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

/**
 * 天气判定（按生物群系）+ "适合干什么"建议。
 *
 * <p>参考上游 mcphone feature/weather/Weather：世界的下雨开关是全局的，
 * 落下来的东西按当地生物群系算——同一个 isRaining 在平原是雨、雪原是雪、
 * 沙漠什么都不落（天光却照样变暗）；下界/末地（无天空）根本没有天气。</p>
 *
 * <p>每种天气对应两个语言键：{@code weather.mcphone.kind.<id>}（天气名）与
 * {@code weather.mcphone.advice.<id>}[.night]（建议文案，晴天分昼夜两套）。</p>
 */
public final class WeatherAdvisor {

    private WeatherAdvisor() {}

    public enum Kind {
        CLEAR("clear", true),
        RAIN("rain", false),
        SNOW("snow", false),
        THUNDER("thunder", false),
        /** 别处在下雨，这里一滴不落（沙漠/恶地），天光仍会变暗。 */
        DRY("dry", false),
        /** 该维度没有天气（下界/末地等无天空维度）。 */
        NONE("none", false);

        private final String id;
        private final boolean nightVariant;

        Kind(String id, boolean nightVariant) {
            this.id = id;
            this.nightVariant = nightVariant;
        }

        public String nameKey() {
            return "weather.mcphone.kind." + id;
        }

        public String adviceKey(boolean night) {
            return "weather.mcphone.advice." + id + (night && nightVariant ? ".night" : "");
        }
    }

    /** 玩家所在地的天气判定。world/player 为 null 时返回 NONE。 */
    public static Kind classify(World world, double posX, double posZ) {
        if (world == null) return Kind.NONE;
        // 无天空维度（下界/末地）直接无天气（GTNH 映射：WorldProvider.hasNoSky 字段）。
        if (world.provider.hasNoSky) return Kind.NONE;
        if (!world.isRaining()) return Kind.CLEAR;
        if (world.isThundering()) return Kind.THUNDER;

        BiomeGenBase biome = world.getBiomeGenForCoords(
            (int) Math.floor(posX), (int) Math.floor(posZ));
        if (biome == null) return Kind.RAIN;
        // 雪原/冷群系落雪；沙漠等 enableRain=false 的群系什么都不落。
        // 1.7.10 该映射里"本群系会下雨"= canSpawnLightningBolt()（雪群系恒 false，故先判雪）。
        if (biome.getEnableSnow()) return Kind.SNOW;
        if (biome.canSpawnLightningBolt()) return Kind.RAIN;
        return Kind.DRY;
    }

    /** 天气名。 */
    public static String name(Kind kind) {
        return net.minecraft.util.StatCollector.translateToLocal(kind.nameKey());
    }

    /** 建议文案（1-2 句；晴天按昼夜取不同文案）。 */
    public static String advice(Kind kind, boolean night) {
        return net.minecraft.util.StatCollector.translateToLocal(kind.adviceKey(night));
    }
}
