package com.november.mcphone.net;

import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import com.november.mcphone.core.ItemPhone;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/**
 * 网络通道：0=末影箱 1=AE2终端 2=传送 3=设备名 4=WaypointSync(S→C)
 * 5=购买App(C→S) 6=解锁状态同步(S→C)
 * 15=PlayTimeSync(S→C) 16=PlayTimeMilestone(C→S)。
 * 17=RequestUnlockedApps(C→S，空包=主动拉取已购列表) 18=PurchaseResult(S→C，购买结果)。
 * 7=好友操作(C→S) 8=聊天消息(C→S) 9=会话/好友全量同步(S→C) 10=消息推送(S→C)
 * 11=图片上传/拉取(C→S)。
 * 12=便签保存/删除(C→S) 13=便签全量同步(S→C) 14=便签印成书(C→S)。
 * Teleport 语义：mode 0=传送到指定传送点 1=绑定当前位置 2=重命名 3=删除。
 */
public final class NetworkHandler {

    public static SimpleNetworkWrapper INSTANCE;

    private NetworkHandler() {}

    public static void init() {
        INSTANCE = cpw.mods.fml.common.network.NetworkRegistry.INSTANCE.newSimpleChannel("mcphone");
        INSTANCE.registerMessage(OpenEnderChest.Handler.class, OpenEnderChest.class, 0, Side.SERVER);
        INSTANCE.registerMessage(OpenAe2.Handler.class, OpenAe2.class, 1, Side.SERVER);
        INSTANCE.registerMessage(Teleport.Handler.class, Teleport.class, 2, Side.SERVER);
        INSTANCE.registerMessage(SetDeviceName.Handler.class, SetDeviceName.class, 3, Side.SERVER);
        INSTANCE.registerMessage(WaypointSync.Handler.class, WaypointSync.class, 4, Side.CLIENT);
        INSTANCE.registerMessage(PurchaseApp.Handler.class, PurchaseApp.class, 5, Side.SERVER);
        INSTANCE.registerMessage(UnlockSync.Handler.class, UnlockSync.class, 6, Side.CLIENT);
        INSTANCE.registerMessage(ChatFriendAction.Handler.class, ChatFriendAction.class, 7, Side.SERVER);
        INSTANCE.registerMessage(ChatMsgSend.Handler.class, ChatMsgSend.class, 8, Side.SERVER);
        INSTANCE.registerMessage(ChatConvSync.Handler.class, ChatConvSync.class, 9, Side.CLIENT);
        INSTANCE.registerMessage(ChatMsgPush.Handler.class, ChatMsgPush.class, 10, Side.CLIENT);
        INSTANCE.registerMessage(ChatImage.Handler.class, ChatImage.class, 11, Side.SERVER);
        // 聊天：登录时全量同步会话/好友数据给客户端（Forge 总线，仅服务端触发）。
        com.november.mcphone.feature.chat.ChatEvents.register();
        INSTANCE.registerMessage(NoteSave.Handler.class, NoteSave.class, 12, Side.SERVER);
        INSTANCE.registerMessage(NoteSync.Handler.class, NoteSync.class, 13, Side.CLIENT);
        INSTANCE.registerMessage(NotePrint.Handler.class, NotePrint.class, 14, Side.SERVER);
        // 便签登录同步（PlayerLoggedInEvent 由 FMLCommonHandler 内部 EventBus 派发，
        // 不是 MinecraftForge.EVENT_BUS——挂错总线监听器永远不会触发）。
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(new com.november.mcphone.feature.notes.NoteEvents());
        // 游玩时长（服务端权威）：S→C 快照推送 + C→S 里程碑确认（一次性问候落盘）。
        // 两侧都注册：1.7.10 单人模式下 init 在客户端线程跑，若按 effectiveSide 门控
        // 会漏注册；监听器内部用 EntityPlayerMP 过滤 + ServerTick 事件仅服务端派发，
        // 客户端侧注册无副作用。
        INSTANCE.registerMessage(PlayTimeSync.Handler.class, PlayTimeSync.class, 15, Side.CLIENT);
        INSTANCE.registerMessage(PlayTimeMilestone.Handler.class, PlayTimeMilestone.class, 16, Side.SERVER);
        // P4（商店默认开 + 体验补齐）：17 = 客户端主动拉取已购列表（上游 RequestPurchasedAppsPacket
        // 同语义），18 = 购买结果权威回流（ok / cannot_afford / purchase_failed）。17/18 是本轮
        // 之前未占用的下一个空闲号（0..16 已分配，见上）。
        INSTANCE.registerMessage(RequestUnlockedApps.Handler.class, RequestUnlockedApps.class, 17, Side.SERVER);
        INSTANCE.registerMessage(PurchaseResult.Handler.class, PurchaseResult.class, 18, Side.CLIENT);
        com.november.mcphone.store.PlayTimeTracker.register();
        // 服务端主 tick 任务泵：在 init() 里【一次性】注册到 FML 总线
        //（ServerTickEvent 只由 MinecraftServer 派发，纯客户端 JVM 永不触发，注册无副作用）。
        // 不能像旧代码那样在 netty 线程懒注册：并发懒注册本身有竞态。
        cpw.mods.fml.common.FMLCommonHandler.instance()
            .bus()
            .register(new ServerTaskDrain());
    }

    public static void sendToServer(IMessage msg) {
        if (INSTANCE != null) INSTANCE.sendToServer(msg);
    }

    // ===================== 服务端主线程派发 =====================

    /**
     * 当前服务端主线程（"Server thread"）。由 ServerTaskDrain 在服务端主 tick 刷新：
     * 集成服每进一个存档都会新建一条 Server 线程（MinecraftServer.startServerThread
     * → new Thread("Server thread")，MinecraftServer.java:748-759），不能只在首次记录。
     * 服务端线程写、netty 线程读，故 volatile。
     */
    private static volatile Thread serverThread;

    /** 队列上限（超过即丢弃并记日志）。 */
    private static final int MAX_QUEUED_TASKS = 4096;

    /** 单 tick 最多执行的任务数：防止一次 tick 被大量任务（如聊天图片）长时间卡住。 */
    private static final int MAX_TASKS_PER_TICK = 256;

    /**
     * 待执行的服务端任务：netty 线程只入队，服务端主线程（ServerTaskDrain）只出队。
     * 有界队列（offer 满即丢）——C→S 包由不受信客户端驱动，无上限会被刷包撑爆内存。
     * size()/isEmpty() 在该实现下是 O(1)。
     *
     * 1.7.10 的 MinecraftServer 没有 addScheduledTask/func_152344_a（该 API 只存在于
     * 客户端 Minecraft，见 build/rfg/minecraft-src/java/net/minecraft/client/Minecraft.java:3006），
     * 故自建队列。
     */
    private static final java.util.concurrent.BlockingQueue<Runnable> SERVER_TASKS =
        new java.util.concurrent.LinkedBlockingQueue<Runnable>(MAX_QUEUED_TASKS);

    /** 积压告警去重（队列排空后允许再次告警）。 */
    private static volatile boolean backlogWarned;

    /** 首次真正执行任务时打一条一次性日志：服务端冒烟时用它确认派发生效（每次启动至多一条）。 */
    private static volatile boolean pumpLogged;

    /**
     * 服务端 C→S 包处理统一入口：1.7.10 的 SimpleImpl handler
     * （{@code SimpleChannelHandlerWrapper.channelRead0}）运行在 netty event-loop 线程
     * （服务端线程名 "Netty IO #N"，NetworkSystem.java:47；客户端 "Netty Client IO #N"，
     * NetworkManager.java:51），直接在 netty 线程访问世界/背包/WorldSavedData 是非线程安全的，
     * 必须排进服务端主 tick 执行。
     *
     * <p><b>判据必须是「本 JVM 有没有活着的服务端实例」，绝不能用
     * {@code FMLCommonHandler.getEffectiveSide()}：1.7.10 的实现是【线程名 == "Server thread"】
     * （FMLCommonHandler.java:152-161），而 handler 在 netty 线程执行 ⇒ 恒判 CLIENT ⇒
     * 旧写法永远走 else 直接 r.run()，SERVER_TASKS 与 ServerTaskDrain 是死代码。</b></p>
     *
     * <p>专用服务器与单机集成服都靠 {@code MinecraftServer.getServer() != null} 判真；
     * 该静态字段只在构造时赋值、永不复位（MinecraftServer.java:88,164），所以退出世界后仍非 null，
     * 必须再判 {@code isServerStopped()}（停服时置 true，:543/:552）。</p>
     *
     * <p>服务端不可用（纯客户端 / 已退出世界 / 停服中）时【安全丢弃并记日志】，
     * 绝不退回 netty 线程直接改世界。玩家上下文不可用时同样丢弃（避免任务里 NPE）。</p>
     */
    public static void runOnServer(MessageContext ctx, Runnable r) {
        if (r == null) return;

        // 1) 上下文/玩家校验：玩家已离线或上下文异常时直接丢弃。
        if (!hasServerPlayer(ctx)) {
            warnDrop("no server-side player");
            return;
        }

        // 2) 可靠判据：有活着的服务端实例。
        net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        if (server == null || server.isServerStopped()) {
            warnDrop("server not available");
            return;
        }

        // 3) 已在服务端主线程：直接执行（防御分支，正常 C→S 路径不会进）。
        Thread main = serverThread;
        if (main != null && Thread.currentThread() == main) {
            r.run();
            return;
        }

        // 4) 入队，由服务端主 tick（ServerTaskDrain）排空。
        if (!SERVER_TASKS.offer(r)) {
            warnDrop("server task queue full (" + MAX_QUEUED_TASKS + ")");
        }
    }

    /** C→S 上下文里是否还有效地挂着服务端玩家（netty 线程上做，避免任务运行时 NPE）。 */
    private static boolean hasServerPlayer(MessageContext ctx) {
        try {
            return ctx != null && ctx.getServerHandler() != null
                && ctx.getServerHandler().playerEntity != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 丢弃任务时记日志（绝不静默，也绝不在 netty 线程补跑）。 */
    private static void warnDrop(String reason) {
        System.err.println("[mcphone] dropped server task (" + reason
            + ") on thread " + Thread.currentThread().getName());
    }

    /**
     * 服务端主 tick 任务泵。必须是 public 具名静态类（踩坑 #7），且在 init() 里一次性注册。
     *
     * <p>选 {@code Phase.END} 的理由：END 是 {@code MinecraftServer.tick()} 的最后一条语句
     * （MinecraftServer.java:661），此时本 tick 的 {@code updateTimeLightAndEntities()} 已返回——
     * 维度/世界/实体/容器迭代全部结束，原版玩家包队列也已在其中排空
     * （:730 {@code networkTick()} → {@code NetworkManager.processReceivedPackets}），
     * 下一个动作就是服务器回到 50ms 主循环。也就是说 END 是本 tick 唯一「没有任何逻辑正在遍历
     * 世界/背包」的静默点：任务对世界、背包、容器的修改不会与任何迭代交叠。而
     * {@code Phase.START}（:607）在 updateTimeLightAndEntities() 之前，任务会与随后的整轮
     * world/player tick 同 tick 交错，且本 tick 的原版包处理结果要等下一 tick 才可见，故不选。</p>
     */
    public static final class ServerTaskDrain {

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onServerTick(cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent event) {
            if (event.phase != cpw.mods.fml.common.gameevent.TickEvent.Phase.END) return;
            // 本方法必然在 "Server thread" 上执行：记录真实线程对象，供 runOnServer 判「已在主线程」。
            serverThread = Thread.currentThread();

            int budget = MAX_TASKS_PER_TICK;
            Runnable r;
            while (budget-- > 0 && (r = SERVER_TASKS.poll()) != null) {
                try {
                    if (!pumpLogged) {
                        pumpLogged = true;
                        System.err.println("[mcphone] server task pump active: first task runs on thread "
                            + Thread.currentThread().getName());
                    }
                    r.run();
                } catch (Throwable t) {
                    // 单个任务失败不能带走服务器 tick（原版 run() 对 tick 抛错是直接停服）。
                    System.err.println("[mcphone] server task failed: " + t);
                    t.printStackTrace();
                }
            }

            if (SERVER_TASKS.isEmpty()) {
                backlogWarned = false;
            } else if (!backlogWarned) {
                backlogWarned = true;
                System.err.println("[mcphone] server task backlog " + SERVER_TASKS.size()
                    + " (per-tick budget " + MAX_TASKS_PER_TICK + "), draining over next ticks");
            }
        }
    }

    // ===================== 消息定义 =====================

    public static class OpenEnderChest implements IMessage {

        public OpenEnderChest() {}

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static class Handler implements IMessageHandler<OpenEnderChest, IMessage> {

            @Override
            public IMessage onMessage(OpenEnderChest msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        // 服务端权威门禁：有价且未购买即拒绝（商店模式的本意，
                        // 与客户端本地开关无关，见 StoreManager.canOpen）。
                        com.november.mcphone.store.StoreManager
                            .openEnderChestIfAllowed(ctx.getServerHandler().playerEntity);
                    });
                return null;
            }
        }
    }

    public static class OpenAe2 implements IMessage {

        public OpenAe2() {}

        @Override
        public void fromBytes(ByteBuf buf) {}

        @Override
        public void toBytes(ByteBuf buf) {}

        public static class Handler implements IMessageHandler<OpenAe2, IMessage> {

            @Override
            public IMessage onMessage(OpenAe2 msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> AppIntegrations.openAe2Terminal(ctx.getServerHandler().playerEntity));
                return null;
            }
        }
    }

    public static class Teleport implements IMessage {

        /** 0=传送到指定点 1=绑定当前位置 2=重命名 3=删除。 */
        public int mode;
        /** mode=0/2/3 时的传送点下标；mode=1 时忽略。 */
        public int index;
        /** mode=1（可空，空则自动命名）/ mode=2 的名称。 */
        public String name = "";

        public Teleport() {}

        public Teleport(int mode, int index, String name) {
            this.mode = mode;
            this.index = index;
            this.name = name == null ? "" : name;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            mode = buf.readByte();
            index = buf.readShort();
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            name = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(mode);
            buf.writeShort(index);
            buf.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public static class Handler implements IMessageHandler<Teleport, IMessage> {

            @Override
            public IMessage onMessage(Teleport msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> AppIntegrations.teleportViaPhone(
                        ctx.getServerHandler().playerEntity, msg.mode, msg.index, msg.name));
                return null;
            }
        }
    }

    /** 服务端 → 客户端：传送点列表全量同步（增删改后立即刷新手机页面）。 */
    public static class WaypointSync implements IMessage {

        public java.util.List<com.november.mcphone.core.ItemPhone.Waypoint> waypoints =
            new java.util.ArrayList<>();

        public WaypointSync() {}

        public WaypointSync(java.util.List<com.november.mcphone.core.ItemPhone.Waypoint> wps) {
            this.waypoints = wps;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int n = buf.readShort();
            waypoints = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                com.november.mcphone.core.ItemPhone.Waypoint w =
                    new com.november.mcphone.core.ItemPhone.Waypoint();
                byte[] nb = new byte[buf.readShort()];
                buf.readBytes(nb);
                w.name = new String(nb, java.nio.charset.StandardCharsets.UTF_8);
                w.x = buf.readDouble();
                w.y = buf.readDouble();
                w.z = buf.readDouble();
                w.dim = buf.readInt();
                w.yaw = buf.readFloat();
                w.pitch = buf.readFloat();
                waypoints.add(w);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(waypoints.size());
            for (com.november.mcphone.core.ItemPhone.Waypoint w : waypoints) {
                byte[] nb = (w.name == null ? "" : w.name).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buf.writeShort(nb.length);
                buf.writeBytes(nb);
                buf.writeDouble(w.x);
                buf.writeDouble(w.y);
                buf.writeDouble(w.z);
                buf.writeInt(w.dim);
                buf.writeFloat(w.yaw);
                buf.writeFloat(w.pitch);
            }
        }

        public static class Handler implements IMessageHandler<WaypointSync, IMessage> {

            @Override
            public IMessage onMessage(WaypointSync msg, MessageContext ctx) {
                // 1.7.10 客户端包处理在 netty 线程：先缓存，客户端 tick 中应用。
                com.november.mcphone.client.ClientHooks.pendingWaypointSync =
                    new java.util.ArrayList<>(msg.waypoints);
                return null;
            }
        }
    }

    public static class SetDeviceName implements IMessage {

        public String name = "";

        public SetDeviceName() {}

        public SetDeviceName(String name) {
            this.name = name;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int len = buf.readableBytes();
            byte[] data = new byte[len];
            buf.readBytes(data);
            name = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public static class Handler implements IMessageHandler<SetDeviceName, IMessage> {

            @Override
            public IMessage onMessage(SetDeviceName msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        EntityPlayerMP p = ctx.getServerHandler().playerEntity;
                        String n = msg.name == null ? "" : msg.name.trim();
                        if (n.length() > 24) n = n.substring(0, 24);
                        ItemStack held = p.getHeldItem();
                        if (held != null && held.getItem() instanceof ItemPhone) {
                            ItemPhone.setDeviceName(held, n);
                        } else {
                            for (ItemStack s : p.inventory.mainInventory) {
                                if (s != null && s.getItem() instanceof ItemPhone) {
                                    ItemPhone.setDeviceName(s, n);
                                    break;
                                }
                            }
                        }
                    });
                return null;
            }
        }
    }

    /** 客户端 → 服务端：购买付费 App（服务端校验背包并扣物，解锁状态按存档持久化）。 */
    public static class PurchaseApp implements IMessage {

        public String appId = "";

        public PurchaseApp() {}

        public PurchaseApp(String appId) {
            this.appId = appId == null ? "" : appId;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            appId = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeBytes(appId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        public static class Handler implements IMessageHandler<PurchaseApp, IMessage> {

            @Override
            public IMessage onMessage(PurchaseApp msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        String id = msg.appId == null ? "" : msg.appId.trim();
                        // 长度上限防御伪造包；合法性（是否付费/已购）在 StoreManager 内校验。
                        if (!id.isEmpty() && id.length() <= 64) {
                            com.november.mcphone.store.StoreManager
                                .handlePurchase(ctx.getServerHandler().playerEntity, id);
                        }
                    });
                return null;
            }
        }
    }

    /** 服务端 → 客户端：当前玩家已购 App 全量同步（登录/购买后）。 */
    public static class UnlockSync implements IMessage {

        public java.util.List<String> appIds = new java.util.ArrayList<>();

        public UnlockSync() {}

        public UnlockSync(java.util.List<String> ids) {
            this.appIds = ids;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            int n = buf.readShort();
            appIds = new java.util.ArrayList<>(Math.min(n, 256));
            for (int i = 0; i < n; i++) {
                // 截断/伪造包防御：剩余字节不足一条记录（1 长度前缀 + ≥0 数据）即止。
                if (buf.readableBytes() < 1) break;
                int len = buf.readUnsignedByte();
                if (buf.readableBytes() < len) break;
                byte[] data = new byte[len];
                buf.readBytes(data);
                appIds.add(new String(data, java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(appIds.size());
            for (String id : appIds) {
                byte[] data = (id == null ? "" : id).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buf.writeByte(Math.min(data.length, 255));
                buf.writeBytes(data, 0, Math.min(data.length, 255));
            }
        }

        public static class Handler implements IMessageHandler<UnlockSync, IMessage> {

            @Override
            public IMessage onMessage(UnlockSync msg, MessageContext ctx) {
                // 1.7.10 客户端包处理在 netty 线程：先缓存，客户端 tick 中应用（同 WaypointSync）。
                com.november.mcphone.client.ClientHooks.pendingUnlockSync =
                    new java.util.ArrayList<>(msg.appIds);
                return null;
            }
        }
    }

    // ===================== 商店：主动拉取 + 结果回流（P4） =====================

    /**
     * 客户端 → 服务端：主动拉取"我买过哪些 App"（空包，没有任何字段）。
     *
     * <p><b>上游对照</b>（november521/mcphone v1.10.2）：{@code
     * platforms/1.20.1-forge/.../feature/store/net/RequestPurchasedAppsPacket.java}
     * ——同样没有字段。上游注释把理由写得很清楚，这里逐条沿用：购买记录存在服务端，
     * 客户端一无所知，所以客户端要主动问；问的是"发包这名玩家买过什么"，服务端从连接
     * 上下文拿玩家，<b>客户端不传任何身份</b>（带上玩家 id 等于给伪造客户端开
     * "查别人买过什么"的后门）。上游由 {@code StoreNetworking.handleRequest} 接住并调
     * {@code sync(player)}；本侧对应 {@link com.november.mcphone.store.StoreManager#handleUnlockRequest}
     * → 现成的 {@code StoreManager.syncTo(player)}（多一道抗刷包节流，理由见该方法注释）。</p>
     *
     * <p><b>为什么需要它</b>：商店模式已改为默认开。"玩家进服"与"登录推送的 UnlockSync
     * 落地"之间有一段窗口；窗口内客户端 {@code StoreClient.unlocked == null}（未同步），
     * 主屏 {@code PhoneUi.orderedForHome()} 会按 {@code needsPurchase()} 把付费 App 过滤掉
     * ——玩家看到付费 App 在主屏"晚一拍才出现"。客户端一能渲染就主动拉一次，把窗口从
     * "等服务端推送"压到一个 RTT。</p>
     */
    public static class RequestUnlockedApps implements IMessage {

        public RequestUnlockedApps() {}

        @Override
        public void fromBytes(ByteBuf buf) {
            // 空包：没有字段可读（与 toBytes 严格对称）。
        }

        @Override
        public void toBytes(ByteBuf buf) {
            // 空包：没有字段可写。
        }

        public static class Handler implements IMessageHandler<RequestUnlockedApps, IMessage> {

            @Override
            public IMessage onMessage(RequestUnlockedApps msg, MessageContext ctx) {
                // 与其它 C→S 包同一套：netty 线程只入队，服务端主 tick 里才碰
                // WorldSavedData / 背包（runOnServer 的判据是"本 JVM 有没有活着的服务端实例"，
                // 不是 effectiveSide）。
                runOnServer(
                    ctx,
                    () -> com.november.mcphone.store.StoreManager
                        .handleUnlockRequest(ctx.getServerHandler().playerEntity));
                return null;
            }
        }
    }

    /**
     * 服务端 → 客户端：这一次购买请求的确切结果。
     *
     * <p><b>线格式</b> = {@code result(byte) + appId(short 长度前缀 UTF-8)}，复用同文件里的
     * {@code readUtf/writeUtf}。appId 回带给客户端，是为了让 UI 判断"这条结论属于哪个 App"
     * ——上一个 App 的失败结论不该串到这一个上面。appId 由 {@code PurchaseApp} 侧限长
     * 64 字符，远小于 writeUtf 的 600 字节封顶。</p>
     *
     * <p><b>为什么需要它</b>：改动前服务端失败只 {@code addChatMessage}（聊天栏），手机里
     * 看不到；客户端只能靠 {@code StoreFront} 的 100 帧倒计时"猜"失败，而且分不清
     * "买不起"和"扣费失败"。本包把结论权威回流给客户端（{@code StoreClient.onPurchaseResult}）。
     * 既有的 {@code UnlockSync} 差集得到的"刚买成"链路<b>原样保留</b>：两条通道并存、
     * 互不覆盖（UnlockSync 负责"已购集合真的变大了"的正向信号，本包负责"这次请求的结论"）。</p>
     */
    public static class PurchaseResult implements IMessage {

        /** 0 = 成功（含"本来就已拥有"的幂等成功：不重复扣费）。 */
        public static final byte RESULT_OK = 0;
        /** 1 = 买不起（服务端只读预检就否了，一个物品都没扣）。 */
        public static final byte RESULT_CANNOT_AFFORD = 1;
        /** 2 = 没成功（原子扣费未通过，或该 App 不在报价表里 / 请求被拒）。 */
        public static final byte RESULT_PURCHASE_FAILED = 2;

        /** 见 RESULT_* 常量。 */
        public byte result;
        /** 这次请求对应的 App id（空串 = 畸形包）。 */
        public String appId = "";

        public PurchaseResult() {}

        public PurchaseResult(String appId, byte result) {
            this.appId = appId == null ? "" : appId;
            this.result = result;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            result = buf.readByte();
            appId = readUtf(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(result);
            writeUtf(buf, appId);
        }

        public static class Handler implements IMessageHandler<PurchaseResult, IMessage> {

            @Override
            public IMessage onMessage(PurchaseResult msg, MessageContext ctx) {
                // 1.7.10 客户端包处理在 netty 线程：只把结论整体发布成 StoreClient 里的一条
                // 不可变快照，绝不在这里碰 UI；UI 每帧读 StoreClient.lastPurchaseResult()。
                com.november.mcphone.client.StoreClient.onPurchaseResult(msg.appId, msg.result);
                return null;
            }
        }
    }

    // ===================== 聊天 App（7-11） =====================

    /** short 长度前缀 UTF 串（1.7.10 自定义包总量须 ≤ 32KB，字符串都很短）。 */
    private static String readUtf(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        if (buf.readableBytes() < len) len = buf.readableBytes();
        byte[] data = new byte[len];
        buf.readBytes(data);
        return new String(data, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 读一段 short 长度前缀的字节，带越界防御：长度超过包剩余可读字节时抛
     * {@link io.netty.handler.codec.DecoderException}，而不是直接分配恶意长度数组
     * （伪造包可把 OOM/负数组异常打进 netty 线程）。仅便签 12/13 两个包接入。
     */
    private static byte[] readSized(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        if (len > buf.readableBytes()) {
            throw new io.netty.handler.codec.DecoderException(
                "mcphone: declared length " + len + " > remaining " + buf.readableBytes());
        }
        byte[] data = new byte[len];
        buf.readBytes(data);
        return data;
    }

    private static void writeUtf(ByteBuf buf, String s) {
        byte[] data = (s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = utf8Clamp(data, 600);
        buf.writeShort(len);
        buf.writeBytes(data, 0, len);
    }

    /**
     * 字节封顶并按 UTF-8 码点边界回退：截断处落在续字节（10xxxxxx）上时
     * 退回到首字节边界，避免 new String 产出 U+FFFD 乱码。
     */
    private static int utf8Clamp(byte[] data, int max) {
        if (data.length <= max) return data.length;
        int len = max;
        while (len > 0 && (data[len] & 0xC0) == 0x80) len--;
        return len;
    }

    /** 客户端 → 服务端：好友操作。op 0=申请(name) 1=接受(uuid) 2=拒绝(uuid) 3=删除(uuid) 4=传送(uuid)。 */
    public static class ChatFriendAction implements IMessage {

        public int op;
        public String payload = "";

        public ChatFriendAction() {}

        public ChatFriendAction(int op, String payload) {
            this.op = op;
            this.payload = payload == null ? "" : payload;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            op = buf.readByte();
            payload = readUtf(buf);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(op);
            writeUtf(buf, payload);
        }

        public static class Handler implements IMessageHandler<ChatFriendAction, IMessage> {

            @Override
            public IMessage onMessage(ChatFriendAction msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> com.november.mcphone.feature.chat.ChatService.handleFriendAction(
                        ctx.getServerHandler().playerEntity, msg.op, msg.payload));
                return null;
            }
        }
    }

    /**
     * 客户端 → 服务端：消息操作。mode 0=发文本(peer,text) 1=拉取历史(peer) 2=标记已读(peer,time)。
     */
    public static class ChatMsgSend implements IMessage {

        public int mode;
        public String peer = "";
        public String text = "";
        public long time;

        public ChatMsgSend() {}

        public ChatMsgSend(int mode, String peer, String text, long time) {
            this.mode = mode;
            this.peer = peer == null ? "" : peer;
            this.text = text == null ? "" : text;
            this.time = time;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            mode = buf.readByte();
            peer = readUtf(buf);
            if (mode == 0) {
                text = readUtf(buf);
            } else if (mode == 2) {
                time = buf.readLong();
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(mode);
            writeUtf(buf, peer);
            if (mode == 0) {
                writeUtf(buf, text);
            } else if (mode == 2) {
                buf.writeLong(time);
            }
        }

        public static class Handler implements IMessageHandler<ChatMsgSend, IMessage> {

            @Override
            public IMessage onMessage(ChatMsgSend msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> com.november.mcphone.feature.chat.ChatService.handleMsg(
                        ctx.getServerHandler().playerEntity, msg.mode, msg.peer, msg.text, msg.time));
                return null;
            }
        }
    }

    /** 服务端 → 客户端：会话/好友/申请/可添加玩家全量同步。 */
    public static class ChatConvSync implements IMessage {

        /** 一行数据：好友/申请/在线玩家三张表共用。 */
        public static class Entry {

            public final String uuid;
            public final String name;
            public final boolean online;
            public final int unread;
            public final String preview;

            public Entry(String uuid, String name, boolean online, int unread, String preview) {
                this.uuid = uuid;
                this.name = name;
                this.online = online;
                this.unread = unread;
                this.preview = preview == null ? "" : preview;
            }
        }

        public final List<Entry> entries;
        public final List<Entry> requests;
        public final List<Entry> addable;

        public ChatConvSync() {
            entries = new java.util.ArrayList<>();
            requests = new java.util.ArrayList<>();
            addable = new java.util.ArrayList<>();
        }

        public ChatConvSync(List<Entry> entries, List<Entry> requests, List<Entry> addable) {
            this.entries = entries;
            this.requests = requests;
            this.addable = addable;
        }

        private static List<Entry> readEntries(ByteBuf buf) {
            int n = buf.readUnsignedByte();
            List<Entry> out = new java.util.ArrayList<>(Math.min(n, 128));
            for (int i = 0; i < n; i++) {
                out.add(new Entry(
                    readUtf(buf), readUtf(buf), buf.readByte() != 0,
                    buf.readUnsignedByte(), readUtf(buf)));
            }
            return out;
        }

        private static void writeEntries(ByteBuf buf, List<Entry> list) {
            buf.writeByte(Math.min(list.size(), 255));
            for (Entry e : list) {
                writeUtf(buf, e.uuid);
                writeUtf(buf, e.name);
                buf.writeByte(e.online ? 1 : 0);
                buf.writeByte(Math.min(e.unread, 99));
                writeUtf(buf, e.preview);
            }
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            entries.addAll(readEntries(buf));
            requests.addAll(readEntries(buf));
            addable.addAll(readEntries(buf));
        }

        @Override
        public void toBytes(ByteBuf buf) {
            writeEntries(buf, entries);
            writeEntries(buf, requests);
            writeEntries(buf, addable);
        }

        public static class Handler implements IMessageHandler<ChatConvSync, IMessage> {

            @Override
            public IMessage onMessage(ChatConvSync msg, MessageContext ctx) {
                // netty 线程：排进并发队列，客户端 tick 主线程应用（同 WaypointSync 模式）。
                com.november.mcphone.feature.chat.client.ChatClient.onConvSync(msg);
                return null;
            }
        }
    }

    /**
     * 服务端 → 客户端：消息推送。
     * kind 0=历史批（拉取响应，多批连续发送，客户端按批追加） 1=单条新消息（追加）
     * 2=图片字节（按需拉取的响应）。
     */
    public static class ChatMsgPush implements IMessage {

        /** kind0 单批条数上限：32KB 包预算下 30 条（每条 meta ≤ 200B 文本）很宽裕。 */
        public static final int HISTORY_BATCH = 30;

        public static class MsgMeta {

            public boolean self;
            public long time;
            public boolean image;
            public String text = "";
            public String imageId = "";
            public int w;
            public int h;
        }

        public int kind;
        public String peer = "";
        /** kind0 分批协议：true = 首批（客户端先清空该会话缓存再追加，避免二次拉取重复）。 */
        public boolean batchStart;
        public List<MsgMeta> messages = new java.util.ArrayList<>();
        /** kind=2 的图片字节。 */
        public byte[] data;
        public int w;
        public int h;
        public String imageId = "";

        public ChatMsgPush() {}

        public static ChatMsgPush history(String peer, List<com.november.mcphone.feature.chat.ChatWorldData.Msg> msgs, UUID self) {
            return history(peer, msgs, self, true);
        }

        /** @param batchStart 首批 true（客户端清空该会话后追加），后续批次 false。 */
        public static ChatMsgPush history(String peer, List<com.november.mcphone.feature.chat.ChatWorldData.Msg> msgs, UUID self, boolean batchStart) {
            ChatMsgPush m = new ChatMsgPush();
            m.kind = 0;
            m.peer = peer;
            m.batchStart = batchStart;
            for (com.november.mcphone.feature.chat.ChatWorldData.Msg msg : msgs) {
                m.messages.add(meta(msg, self));
            }
            return m;
        }

        public static ChatMsgPush single(String peer, com.november.mcphone.feature.chat.ChatWorldData.Msg msg, boolean self) {
            ChatMsgPush m = new ChatMsgPush();
            m.kind = 1;
            m.peer = peer;
            MsgMeta meta = meta(msg, null);
            meta.self = self;
            m.messages.add(meta);
            if (msg.kind == 1) m.imageId = msg.imageId;
            return m;
        }

        public static ChatMsgPush imageData(String peer, com.november.mcphone.feature.chat.ChatWorldData.Msg msg) {
            ChatMsgPush m = new ChatMsgPush();
            m.kind = 2;
            m.peer = peer;
            m.imageId = msg.imageId;
            m.data = msg.data;
            m.w = msg.w;
            m.h = msg.h;
            return m;
        }

        private static MsgMeta meta(com.november.mcphone.feature.chat.ChatWorldData.Msg msg, UUID self) {
            MsgMeta meta = new MsgMeta();
            meta.time = msg.time;
            meta.image = msg.kind == 1;
            meta.text = msg.text;
            meta.imageId = msg.imageId;
            meta.w = msg.w;
            meta.h = msg.h;
            if (self != null) meta.self = msg.sender.equals(self);
            return meta;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            kind = buf.readByte();
            peer = readUtf(buf);
            if (kind == 0) batchStart = buf.readByte() != 0;
            if (kind == 2) {
                imageId = readUtf(buf);
                w = buf.readInt();
                h = buf.readInt();
                int len = Math.min(buf.readInt(), 32767); // 伪造/截断包防御。
                if (len > 0 && buf.isReadable(len)) {
                    data = new byte[len];
                    buf.readBytes(data);
                }
                return;
            }
            int n = kind == 0 ? buf.readUnsignedShort() : 1;
            for (int i = 0; i < n; i++) {
                MsgMeta meta = new MsgMeta();
                meta.self = buf.readByte() != 0;
                meta.time = buf.readLong();
                meta.image = buf.readByte() != 0;
                if (meta.image) {
                    meta.imageId = readUtf(buf);
                    meta.w = buf.readInt();
                    meta.h = buf.readInt();
                } else {
                    meta.text = readUtf(buf);
                }
                messages.add(meta);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(kind);
            writeUtf(buf, peer);
            if (kind == 0) buf.writeByte(batchStart ? 1 : 0);
            if (kind == 2) {
                writeUtf(buf, imageId);
                buf.writeInt(w);
                buf.writeInt(h);
                byte[] d = data == null ? new byte[0] : data;
                buf.writeInt(d.length);
                buf.writeBytes(d);
                return;
            }
            if (kind == 0) {
                buf.writeShort(Math.min(messages.size(), 200));
            }
            for (MsgMeta meta : messages) {
                buf.writeByte(meta.self ? 1 : 0);
                buf.writeLong(meta.time);
                buf.writeByte(meta.image ? 1 : 0);
                if (meta.image) {
                    writeUtf(buf, meta.imageId);
                    buf.writeInt(meta.w);
                    buf.writeInt(meta.h);
                } else {
                    // 预览文本 200 字节封顶（原 600B × 200 条会逼近 32KB 包上限）。
                    byte[] data = (meta.text == null ? "" : meta.text)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    int len = utf8Clamp(data, 600);
                    buf.writeShort(len);
                    buf.writeBytes(data, 0, len);
                }
            }
        }

        public static class Handler implements IMessageHandler<ChatMsgPush, IMessage> {

            @Override
            public IMessage onMessage(ChatMsgPush msg, MessageContext ctx) {
                // netty 线程：排进并发队列，客户端 tick 主线程应用。
                if (msg.kind == 2) {
                    com.november.mcphone.feature.chat.client.ChatClient.onImageData(msg);
                } else {
                    com.november.mcphone.feature.chat.client.ChatClient.onMsgPush(msg);
                }
                return null;
            }
        }
    }

    /** 客户端 → 服务端：图片上传(op=0, 带 JPEG 字节)/按需拉取(op=1, 按 imageId)。 */
    public static class ChatImage implements IMessage {

        public int op;
        public String peer = "";
        public String imageId = "";
        public byte[] data;
        public int w;
        public int h;

        public ChatImage() {}

        public ChatImage(int op, String peer, String imageId, byte[] data, int w, int h) {
            this.op = op;
            this.peer = peer == null ? "" : peer;
            this.imageId = imageId == null ? "" : imageId;
            this.data = data;
            this.w = w;
            this.h = h;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            op = buf.readByte();
            peer = readUtf(buf);
            if (op == 0) {
                w = buf.readInt();
                h = buf.readInt();
                int len = buf.readInt();
                // 防伪造超大包：1.7.10 自定义包本身 ≤ 32KB，这里再按配置上限掐一道。
                if (len < 0 || len > 32 * 1024) {
                    len = 0;
                }
                data = new byte[len];
                buf.readBytes(data);
            } else {
                imageId = readUtf(buf);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(op);
            writeUtf(buf, peer);
            if (op == 0) {
                buf.writeInt(w);
                buf.writeInt(h);
                byte[] d = data == null ? new byte[0] : data;
                buf.writeInt(d.length);
                buf.writeBytes(d);
            } else {
                writeUtf(buf, imageId);
            }
        }

        public static class Handler implements IMessageHandler<ChatImage, IMessage> {

            @Override
            public IMessage onMessage(ChatImage msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        EntityPlayerMP player = ctx.getServerHandler().playerEntity;
                        if (msg.op == 0) {
                            com.november.mcphone.feature.chat.ChatService.handleImageUpload(
                                player, msg.peer, msg.data, msg.w, msg.h);
                        } else {
                            com.november.mcphone.feature.chat.ChatService.handleImageRequest(
                                player, msg.peer, msg.imageId);
                        }
                    });
                return null;
            }
        }
    }

    /** 客户端 → 服务端：保存/删除便签。数据存 NoteWorldData（随存档持久化），改后回推全量。 */
    public static class NoteSave implements IMessage {

        public static final byte ACTION_SAVE = 0;
        public static final byte ACTION_DELETE = 1;

        /** 0=保存 1=删除。 */
        public byte action;
        /** 便签 id（新建传 0，服务端分配）。 */
        public int id;
        public String title = "";
        public String body = "";

        public NoteSave() {}

        public NoteSave(byte action, int id, String title, String body) {
            this.action = action;
            this.id = id;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            action = buf.readByte();
            id = buf.readInt();
            // readSized 校验长度 ≤ 剩余字节，伪造包的超长长度前缀直接 DecoderException。
            title = new String(readSized(buf), java.nio.charset.StandardCharsets.UTF_8);
            body = new String(readSized(buf), java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(action);
            buf.writeInt(id);
            byte[] t = title.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(t.length);
            buf.writeBytes(t);
            byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            buf.writeShort(b.length);
            buf.writeBytes(b);
        }

        public static class Handler implements IMessageHandler<NoteSave, IMessage> {

            @Override
            public IMessage onMessage(NoteSave msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> {
                        // 长度上限防御伪造包（正文 2000 字符 UTF-8 ≤ 8KB，short 前缀放得下）；
                        // 条数上限与 id 合法性在 NoteServer 内校验。
                        String title = msg.title == null ? "" : msg.title;
                        String body = msg.body == null ? "" : msg.body;
                        if (lenOk(title, 128) && lenOk(body, 8192)) {
                            com.november.mcphone.feature.notes.NoteServer.handleSave(
                                ctx.getServerHandler().playerEntity,
                                msg.action == NoteSave.ACTION_DELETE
                                    ? NoteSave.ACTION_DELETE
                                    : NoteSave.ACTION_SAVE,
                                msg.id,
                                title,
                                body);
                        }
                    });
                return null;
            }

            private static boolean lenOk(String s, int maxBytes) {
                return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= maxBytes;
            }
        }
    }

    /**
     * 服务端 → 客户端：当前玩家便签同步（登录/增删改后），分批传输。
     *
     * <p>1.7.10 自定义包上限 32767 字节，200 条满编便签一次发会超限，故拆批：
     * 线格式 = count(short) + offset(int) + total(int) + count×条目
     * (id int, title short+utf8, body short+utf8, modified long)。
     * 客户端按 offset 拼装、total 判断收齐（见 ClientHooks 累加器）。
     * {@code total == 0} 表示服务端没有便签，仍发一条空批触发导入判断。</p>
     */
    public static class NoteSync implements IMessage {

        /** 本批条目。 */
        public java.util.List<com.november.mcphone.feature.notes.Note> notes =
            new java.util.ArrayList<>();
        /** 本批在全量列表中的起始下标（0 起始的第一批必然 offset=0）。 */
        public int offset;
        /** 全量列表总条数（不是本批条数）。 */
        public int total;

        public NoteSync() {}

        public NoteSync(java.util.List<com.november.mcphone.feature.notes.Note> notes,
                        int offset, int total) {
            this.notes = notes;
            this.offset = offset;
            this.total = total;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            // 与 toBytes 严格对称：short count + int offset + int total + 条目。
            int n = buf.readUnsignedShort();
            offset = buf.readInt();
            total = buf.readInt();
            // 防伪造包：每条至少 16 字节（id4+title2+body2+modified8），声明条数
            // 超过剩余字节可容纳的条数时直接 DecoderException，不分配大列表。
            if ((long) n * 16 > buf.readableBytes()) {
                throw new io.netty.handler.codec.DecoderException(
                    "mcphone: NoteSync count " + n + " > remaining " + buf.readableBytes());
            }
            notes = new java.util.ArrayList<>(Math.min(n, 512));
            for (int i = 0; i < n; i++) {
                com.november.mcphone.feature.notes.Note note =
                    new com.november.mcphone.feature.notes.Note();
                note.id = buf.readInt();
                note.title = new String(readSized(buf), java.nio.charset.StandardCharsets.UTF_8);
                note.body = new String(readSized(buf), java.nio.charset.StandardCharsets.UTF_8);
                note.modified = buf.readLong();
                notes.add(note);
            }
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeShort(notes.size());
            buf.writeInt(offset);
            buf.writeInt(total);
            for (com.november.mcphone.feature.notes.Note n : notes) {
                byte[] t = (n.title == null ? "" : n.title)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] b = (n.body == null ? "" : n.body)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                buf.writeInt(n.id);
                buf.writeShort(t.length);
                buf.writeBytes(t);
                buf.writeShort(b.length);
                buf.writeBytes(b);
                buf.writeLong(n.modified);
            }
        }

        public static class Handler implements IMessageHandler<NoteSync, IMessage> {

            @Override
            public IMessage onMessage(NoteSync msg, MessageContext ctx) {
                // 1.7.10 客户端包处理在 netty 线程：分批包按序进入累加器，客户端 tick
                // 中把收齐的全量列表交给 NotesClientCache（同 WaypointSync 模式）。
                com.november.mcphone.client.ClientHooks.accumulateNoteSync(
                    msg.notes, msg.offset, msg.total);
                return null;
            }
        }
    }

    /** 客户端 → 服务端：把便签印成一本成书（NotePrinter 生成 WrittenBook NBT 放入背包）。 */
    public static class NotePrint implements IMessage {

        public int id;

        public NotePrint() {}

        public NotePrint(int id) {
            this.id = id;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            id = buf.readInt();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeInt(id);
        }

        public static class Handler implements IMessageHandler<NotePrint, IMessage> {

            @Override
            public IMessage onMessage(NotePrint msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> com.november.mcphone.feature.notes.NoteServer.handlePrint(
                        ctx.getServerHandler().playerEntity, msg.id));
                return null;
            }
        }
    }

    /** 服务端 → 客户端：游玩时长快照（登录/周期推送，服务端权威，单位现实 tick）。 */
    public static class PlayTimeSync implements IMessage {

        public long sessionTicks;
        public long totalTicks;
        public boolean milestone3hShown;
        public boolean milestone100hShown;

        public PlayTimeSync() {}

        public PlayTimeSync(long sessionTicks, long totalTicks,
                            boolean milestone3hShown, boolean milestone100hShown) {
            this.sessionTicks = sessionTicks;
            this.totalTicks = totalTicks;
            this.milestone3hShown = milestone3hShown;
            this.milestone100hShown = milestone100hShown;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            sessionTicks = buf.readLong();
            totalTicks = buf.readLong();
            byte flags = buf.readByte();
            milestone3hShown = (flags & 1) != 0;
            milestone100hShown = (flags & 2) != 0;
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeLong(sessionTicks);
            buf.writeLong(totalTicks);
            byte flags = (byte) ((milestone3hShown ? 1 : 0) | (milestone100hShown ? 2 : 0));
            buf.writeByte(flags);
        }

        public static class Handler implements IMessageHandler<PlayTimeSync, IMessage> {

            @Override
            public IMessage onMessage(PlayTimeSync msg, MessageContext ctx) {
                // 1.7.10 客户端包处理在 netty 线程：先缓存，客户端 tick 中应用（同 WaypointSync）。
                com.november.mcphone.client.ClientHooks.pendingPlayTimeSync =
                    new com.november.mcphone.client.enhance.PlayTimeClient.Snapshot(
                        msg.sessionTicks, msg.totalTicks,
                        msg.milestone3hShown, msg.milestone100hShown);
                return null;
            }
        }
    }

    /** 客户端 → 服务端：里程碑问候已提示（0=本局 3 小时 1=世界总 100 小时），落盘保证一次性。 */
    public static class PlayTimeMilestone implements IMessage {

        /** 0 = 本局连续 3 小时；1 = 世界总时长 100 小时。 */
        public int milestone;

        public PlayTimeMilestone() {}

        public PlayTimeMilestone(int milestone) {
            this.milestone = milestone;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            milestone = buf.readByte();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeByte(milestone);
        }

        public static class Handler implements IMessageHandler<PlayTimeMilestone, IMessage> {

            @Override
            public IMessage onMessage(PlayTimeMilestone msg, MessageContext ctx) {
                runOnServer(
                    ctx,
                    () -> com.november.mcphone.store.PlayTimeTracker
                        .handleMilestone(ctx.getServerHandler().playerEntity, msg.milestone));
                return null;
            }
        }
    }
}