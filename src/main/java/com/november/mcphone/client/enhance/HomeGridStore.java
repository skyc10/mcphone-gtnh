package com.november.mcphone.client.enhance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.server.integrated.IntegratedServer;

import com.november.mcphone.client.PhoneCanvas;

/**
 * 主屏图标顺序（客户端本地，按存档隔离）。
 *
 * <p>参考上游 config/mcphone/installed/&lt;存档&gt;.json 的思路：以"存档标识"为键
 * 存到 .minecraft/mcphone/homegrid/&lt;存档&gt;.properties（单键 order=逗号分隔 id）。
 * 存档标识 = 单机存档文件夹名 / 服务器地址。文件不存在时回落到全局
 * {@link PhoneCanvas#getAppOrder()}（应用管理页 ↑/↓ 维护的那份）。</p>
 *
 * <p>读取时过滤未知 id（附属被卸载后自动清理），已知但未列出的 App 按注册顺序
 * 追加到末尾（新装 App 默认排最后）。</p>
 */
public final class HomeGridStore {

    private HomeGridStore() {}

    /** 解析主屏顺序：stored（可空）优先，其次全局顺序表，最后按传入顺序兜底。 */
    public static List<String> resolveOrder(Collection<String> knownIds) {
        List<String> out = new ArrayList<>();
        for (String id : loadStored()) {
            if (knownIds.contains(id) && !out.contains(id)) out.add(id);
        }
        for (String id : knownIds) {
            if (!out.contains(id)) out.add(id);
        }
        return out;
    }

    /** 拖拽落定：写本存档顺序文件，并同步一份到全局顺序表（应用管理页展示一致）。 */
    public static void saveOrder(List<String> ids) {
        Properties p = new Properties();
        p.setProperty("order", String.join(",", ids));
        File f = orderFile();
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, "MCphone home grid order (per save)");
        } catch (IOException ignored) {}
        PhoneCanvas.setAppOrder(new ArrayList<>(ids));
    }

    // ===================== 存取 =====================

    private static List<String> loadStored() {
        List<String> out = new ArrayList<>();
        File f = orderFile();
        if (f == null || !f.isFile()) return out;
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException ignored) {
            return out;
        }
        for (String s : p.getProperty("order", "").split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static File orderFile() {
        String key = saveKey();
        if (key == null) return null;
        File dir = new File(PhoneCanvas.baseDir(), "homegrid");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, key + ".properties");
    }

    /** 当前存档标识：单机 = 存档文件夹名；联机 = 服务器地址；都拿不到 = null。 */
    private static String saveKey() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.isSingleplayer()) {
            IntegratedServer server = mc.getIntegratedServer();
            if (server != null) return sanitize(server.getFolderName());
        }
        WorldClient world = mc.theWorld;
        if (world != null && mc.getNetHandler() != null
                && mc.getNetHandler().getNetworkManager() != null
                && mc.getNetHandler().getNetworkManager().getSocketAddress() != null) {
            return sanitize(mc.getNetHandler().getNetworkManager().getSocketAddress().toString());
        }
        return null;
    }

    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        String s = sb.toString();
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
