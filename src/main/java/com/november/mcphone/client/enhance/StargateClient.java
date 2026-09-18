package com.november.mcphone.client.enhance;

/**
 * 客户端星门禁用 App 名单缓存：来自服务端 {@code StargateSync}(id 19, S→C) 的全量名单，
 * 由 ClientHooks 在客户端 tick 主线程落地（仿 PlayTimeClient/WaypointSync 模式）。
 *
 * <p><b>为什么需要同步</b>：{@code config/mcphone-stargate.cfg} 只在服务端加载
 * （{@code MinecraftServer#getFile}，{@code core/StargateConfig}），客户端无从得知
 * 哪个 App 被服务器规则禁用；改动前图标照样渲染、点击才被服务端拒绝
 * （toast「已被服务器规则（星门）关闭」）。同步后主屏
 * {@code PhoneUi.orderedForHome()} 按名单直接过滤——禁了就看不见；
 * 服务端 toast 拒绝逻辑原样保留，兜住直达热键等旁路。</p>
 *
 * <p><b>兼容性（安全退化）</b>：旧服务端不发本包 → 这里保持空集 →
 * {@link #isHidden} 恒 false → 主屏全显示，与改动前完全一致。</p>
 */
public final class StargateClient {

    /** 当前被服务器规则禁用的 App id（不可变；空集 = 无禁用/未同步）。volatile：netty 写、主线程读。 */
    private static volatile java.util.Set<String> hidden =
        java.util.Collections.<String>emptySet();

    private StargateClient() {}

    /** 服务端同步落地（客户端 tick 主线程调用；整体替换，不做增量合并）。 */
    public static void onSync(java.util.List<String> ids) {
        hidden = ids == null || ids.isEmpty()
            ? java.util.Collections.<String>emptySet()
            : java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(ids));
    }

    /** 离开世界时清空：换存档后未重新同步前不得隐藏任何 App（安全退化）。 */
    public static void reset() {
        hidden = java.util.Collections.emptySet();
    }

    /** 该 App 是否被服务器规则（星门）禁用（未同步/名单为空时恒 false）。 */
    public static boolean isHidden(String appId) {
        return hidden.contains(appId);
    }

    /** 同步到达后主屏即时生效：主屏开着才重建（改树走 PhoneUi 延迟队列，踩坑 #3）。 */
    public static void refreshPhoneIfOpen() {
        com.november.mcphone.client.scene.PhoneUi.postAction(() -> {
            com.november.mcphone.client.scene.PhoneUi ui =
                com.november.mcphone.client.scene.PhoneUi.ACTIVE;
            // 名单只影响主屏网格；App 页打开时不必打扰（回主屏本就会重建）。
            if (ui != null && ui.isHome()) {
                ui.rebuildPage();
            }
        });
    }
}