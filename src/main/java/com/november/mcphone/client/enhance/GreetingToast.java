package com.november.mcphone.client.enhance;

import java.time.LocalDateTime;

import net.minecraft.util.StatCollector;

import com.november.mcphone.client.scene.PhoneUi;
import com.november.mcphone.net.NetworkHandler;

/**
 * 打开手机时的一次性问候 Toast（欢迎回来 / 深夜关怀 / 连续游玩 3h / 世界总 100h）。
 *
 * <p>由 ClientHooks 客户端 tick 驱动（仅手机打开期间）。里程碑"一次性"由服务端
 * {@code PlayTimeData} 按玩家+存档持久化，客户端提示后回 {@code PlayTimeMilestone}
 * (id 16) 落盘；同会话内用位掩码兜底，避免回包在途时重复提示。</p>
 */
public final class GreetingToast {

    /** 本次开手机已提示过（每次打开手机最多一句）。 */
    private static boolean shownThisOpen;

    /** 本会话已提示过的里程碑（bit0 = 3h，bit1 = 100h）。 */
    private static int shownMilestones;

    private GreetingToast() {}

    /** 离开世界：全部复位（换存档/重进后可再次欢迎）。 */
    public static void onWorldLeave() {
        shownThisOpen = false;
        shownMilestones = 0;
    }

    /** 时钟页顶部常驻问候（按现实时段；深夜单独文案）。 */
    public static String bandText() {
        int hour = LocalDateTime.now().getHour();
        Greeting.Kind kind = Greeting.isLateNight(hour)
            ? Greeting.Kind.LATE_NIGHT : Greeting.bandOf(hour);
        return StatCollector.translateToLocal(kind.key());
    }

    /** 手机打开期间每客户端 tick 调用：挑一句问候，Toast 一次。 */
    public static void onClientTick() {
        if (shownThisOpen) return;
        PhoneUi ui = PhoneUi.ACTIVE;
        if (ui == null) return;
        shownThisOpen = true;

        int hour = LocalDateTime.now().getHour();
        Greeting.Choice c = Greeting.choose(
            hour,
            PlayTimeClient.sessionTicks(),
            PlayTimeClient.totalTicks(),
            PlayTimeClient.isMilestone3hShown(),
            PlayTimeClient.isMilestone100hShown());

        if (c.kind == Greeting.Kind.LONG_SESSION) {
            sendMilestoneIfNew(ui, 0, c);
        } else if (c.kind == Greeting.Kind.MILESTONE) {
            sendMilestoneIfNew(ui, 1, c);
        } else {
            toast(ui, c);
        }
    }

    /** 里程碑问候：本会话没提示过才弹，并回包让服务端落盘。 */
    private static void sendMilestoneIfNew(PhoneUi ui, int index, Greeting.Choice c) {
        int bit = 1 << index;
        if ((shownMilestones & bit) != 0) return;
        shownMilestones |= bit;
        NetworkHandler.sendToServer(new NetworkHandler.PlayTimeMilestone(index));
        toast(ui, c);
    }

    private static void toast(PhoneUi ui, Greeting.Choice c) {
        String text = c.kind.hasArg()
            ? StatCollector.translateToLocalFormatted(c.kind.key(), c.arg)
            : StatCollector.translateToLocal(c.kind.key());
        PhoneUi.postAction(() -> {
            PhoneUi active = PhoneUi.ACTIVE;
            if (active != null) active.toast(text);
        });
    }
}
