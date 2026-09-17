# MCphone 附属 App 开发指南（SPI）

MCphone 提供基于 Qz-UILib 场景渲染的 App 扩展接口。

> **依赖口径（v1.0.3 事实核查）**：附属**不是**"只依赖 `api` 包"——
> `IPhoneApp.createPage/onActivate/onShiftActivate` 的参数类型是内部类
> `com.november.mcphone.client.scene.PhoneUi`（`PhoneUi` 实现 `PhoneContext`）；
> `PhoneContext.waypoints()` 返回 `core.ItemPhone$Waypoint`；
> `glassCard(...)` 的档位参数是 `client.enhance.PhoneGlass$Tier`。
> 因此附属需对本体 **dev jar 全量 `compileOnly`**（与三个官方附属一致）。
> "收窄到 api 自足"是既定目标（破坏性变更，路线图 C8），落地前以本文档为准。

## 一、注册方式

两种（任选）：

1. **代码注册（附属惯例）**：你的 mod 在客户端 init 阶段调用
   `PhoneApi.register(new MyApp())`。
2. **ServiceLoader 自动发现（幂等兜底）**：在你的 jar 里放
   `META-INF/services/com.november.mcphone.api.IPhoneApp`，内容为实现类全限定名。
   MCphone 在 postInit 阶段通过 ServiceLoader 收集。两条路都通、先到先得；重复注册因 id
   冲突被幂等忽略，不是二选一的硬要求。

> **注册冲突怎么被发现**：`register` 冲突时**后注册者被忽略并返回 `false`**——代码注册请自查返回值；
> ServiceLoader 路径冲突只向 stderr 打一行 `[mcphone] Failed to load addon app`，属静默降级：
> 附属请在注册后自查 `PhoneApi.byId(你的id) != null`，不要把冲突降级误判为"已加载"。

## 二、核心类型

| 类型 | 作用 |
|------|------|
| `IPhoneApp` | App 接口：id/名称/图标/页面或直达动作 |
| `PhoneAppBase` | 便捷抽象基类（推荐继承） |
| `PhoneContext` | 运行上下文：场景、导航、Toast、网络、传送点 |
| `PhoneWidgets` | 常用控件构建器（文本/按钮/卡片/信息行…） |
| `PhoneAppConfig` | 每 App 持久化 KV 配置（跨存档） |
| `PhoneApi` | 注册表：register / byId / apps / orderedApps |

> **液态玻璃（v3 新增，review F7）**：玻璃**默认开启**，此时 `PhoneWidgets.card(...)` /
> `button(...)` 的底色是**玻璃令牌**（深色 `0x5A14181C` 级、圆角 12），不再是旧的 `0x33FFFFFF` 白蒙层 /
> `0xFF3A414D` 实心灰；`primaryButton` 仍是实心 accent（不上玻璃）。
> 附属 App 若要自己写文字色，请用 `PhoneWidgets.glassText()` / `glassMuted()`
> （= `PhoneGlass.text()/muted()`，按档配对），**或保证"底色与文字成对"**——直接把浅色文字压在很薄的玻璃上
> （浅色壁纸 + 极薄档 + 小字号）会出现低于 4.5:1 的对比度。
> 玻璃关闭 / Qz 玻璃类缺失时 `card`/`button` 回落为旧值（`PANEL` + 8px / `BTN_BG` + 8px），公开方法签名未变。
>
> **显式玻璃入口（HEAD 已新增，勿错过）**：
> `PhoneWidgets.glassCard(ctx[, Tier])` / `glassButton(ctx, parent, label, onClick[, Tier])`——
> 不跟随全局开关、按指定档位（默认配置档）挂玻璃；文字色用 `glassText()` / `glassMuted()`（按档配对）。
> 档位类型是内部类 `com.november.mcphone.client.enhance.PhoneGlass$Tier`。

### App 两种形态

- **页面型**（默认）：点击图标后 `createPage(PhoneUi ui)` 构建页面场景树。
- **直达型**（`isDirectAction() = true`）：点击图标立即执行 `onActivate(PhoneUi ui, boolean shift)`，
  不打开页面（如拍照、开末影箱）。
- 页面型额外支持 `onShiftActivate(PhoneUi ui)`：Shift+点击图标的快捷动作
  （内建传送用它实现"快速绑定当前位置"）。

⚠ 三个回调的真签名参数都是 **`PhoneUi`**（`com.november.mcphone.client.scene.PhoneUi`，
本体内部类，实现 `PhoneContext`）——覆写时写 `PhoneContext` **编译不过**。

### 图标（三选一，优先级从高到低）

1. `iconTexture()` —— 纹理路径（推荐）：`"mymod:textures/ui/myapp.png"`，
   按 128x128 解析，放在 `assets/mymod/textures/ui/`。
2. `iconItem()` —— 物品图标（`new ItemStack(...)`）。
3. `iconGlyph()` —— 1-2 个字符画在圆角底板上。

底板颜色：`iconColor()`（ARGB）。

## 三、最小示例（页面型）

```java
import club.heiqi.uilib.ui.scene.node.SceneNode;

import com.november.mcphone.api.*;
import com.november.mcphone.client.scene.PhoneUi;

public final class MyStatusApp extends PhoneAppBase {

    public MyStatusApp() {
        super("mystatus", "app.mymod.status", 0xFF4E8E5E); // id / 名称语言键 / 底色
    }

    @Override
    public SceneNode createPage(PhoneUi ui) { // 真签名：createPage(PhoneUi)——写 PhoneContext 编译不过
        SceneNode page = PhoneWidgets.scrollColumn(ui); // PhoneUi 实现 PhoneContext，可直接传
        page.appendChild(PhoneWidgets.title(ui, ui.tr("app.mymod.status")));
        page.appendChild(PhoneWidgets.infoRow(ui, "生物群系", biomeName));
        PhoneWidgets.primaryButton(ui, page, "刷新", () -> {
            // 按钮回调已自动进入延迟队列，在这里改树/挂载是安全的
            ui.toast("已刷新");
        });
        return page;
    }
}
```

注册（客户端 init）：

```java
PhoneApi.register(new MyStatusApp());
```

语言文件（`assets/mymod/lang/zh_CN.lang`）：

```
app.mymod.status=我的状态
```

### 直达型示例

```java
public final class MyPhotoApp extends PhoneAppBase {
    public MyPhotoApp() { super("myphoto", "app.mymod.photo", 0xFF50586E); }

    @Override
    public boolean isDirectAction() { return true; }

    @Override
    public void onActivate(PhoneUi ui, boolean shift) {
        ui.closePhone();
        // shift = Shift+点击，可自行区分语义
    }
}
```

## 四、PhoneContext 可用能力

| 方法 | 说明 |
|------|------|
| `runtime()` | Qz 场景运行时（`runtime().mount(...)` 挂自定义控件） |
| `phoneStack()` | 手机物品栈（只读） |
| `panelWidth() / panelHeight()` | 面板尺寸（原生像素） |
| `scaledFont(int)` | 按用户字体缩放换算字号（**页面文字一律用它**）。自绘按钮另用 `PhoneWidgets.buttonFontSize(ctx)`（= scaledFont × 设置页"按钮缩放"），不用它你的按钮不跟随按钮缩放滑条 |
| `tr(key)` | 本地化 |
| `clock()` | 世界时钟 Signal（可 `runtime().bindText(node, ctx.clock())`） |
| `openApp(id) / backHome() / isHome() / closePhone()` | 导航。**`openApp` 是同步建页**：在点击回调里直调＝在输入分发中改树（CME 风险），请 `ctx.post(() -> ctx.openApp(id))` |
| `toast(msg)` | 手机内提示 |
| `post(runnable)` | 延迟执行（**回调里改树必用**） |
| `sendToServer(msg)` | 发包 |
| `waypoints()` | 已同步到客户端的传送点列表 |

## 五、持久化配置（PhoneAppConfig）

跨存档保存在 `.minecraft/mcphone/appdata/<appId>.properties`：

```java
PhoneAppConfig cfg = PhoneAppConfig.forApp("mystatus");
boolean b = cfg.getBoolean("enabled", true);
cfg.putBoolean("enabled", !b);
cfg.putInt("level", 3);
```

> **注意**：`forApp()` 每次调用都**新建实例并重读盘**（无内存缓存）——同一 App 多处各自持有
> `PhoneAppConfig` 会互相覆盖（先写的一方丢，最后写入者胜），请在一处持有并复用；
> `save()` **静默吞 IOException**（写失败无感知，需要可靠性自行校验）；
> 可用写入方法只有 `put / putInt / putBoolean / remove`（**无 `putLong/putDouble`**）。

## 六、图标位置

玩家在手机"应用管理"里用 ↑/↓ 调整主屏图标顺序，持久化在
`.minecraft/mcphone/settings.properties` 的 `appOrder`。
附属 App 注册后默认排在最后，玩家可自行上移。

## 七、注意事项

1. **回调里改树必须包 `ctx.post(...)`**：Qz-UILib 输入路由在迭代中派发事件，
   同步 mount/dispose 会抛 ConcurrentModificationException。
   `PhoneWidgets.button / primaryButton / onClick` 已自动包装。
2. 页面构建是**一次性建树**（每次 `createPage` 全量重建）：动态外观用 `ctx.runtime().bind(signal, ...)` 派生，
   不要在树里轮询世界状态。**"一次性"≠"只建一次"**：设置页改字号/按钮缩放/界面缩放/玻璃/主题文字色、
   商店同步、传送点/便签同步、热键捕获等十余条路径都会触发**整树重建**（宿主 `rebuildShellTree` 不做就地
   清子节点而是整树替换）——不要跨重建缓存任何 `SceneNode` 引用，也不要把注册/监听写进 `createPage`。
3. 文本输入是受控组件：`onChange` 里必须把值写回你的 `Signal`，保存时从 Signal 读。
4. 联网操作用你自己的网络包（`ctx.sendToServer` 仅能发 MCphone 通道已注册的消息）。
5. App 全部是客户端对象；服务端逻辑写在你的 mod 里。
6. **重建触发源远不止缩放**（上一条的完整清单）——不要跨重建缓存场景节点引用；页外状态（静态/单例/
   `PhoneAppConfig`）才是跨重建留存状态的正确去处。
7. **玻璃配色（v3）**：玻璃开启时 `card` / `button` 的底色是**玻璃令牌**（深色、圆角 12），
   附属 App 自定义文字请用 `PhoneWidgets.glassText()` / `glassMuted()`，**或保证底色与文字成对**
   （最坏情况是"浅色壁纸 + 极薄档 + 小字号"，浅字压在亮雾底上会低于 4.5:1 基线）。
   档位/强度由用户在手机"设置 → 显示"里改，`PhoneGlass.Tier` / `PhoneCanvas` 只是读数来源，
   附属不需要、也不应该缓存它们。

## 八、每 App 快捷键

玩家可以在手机"应用管理"页为任意 App 绑定一个键盘快捷键：点该行的"键"按钮
进入捕获态，再按目标组合键即完成绑定。格式为 `主键` 或
`SHIFT+主键` / `CTRL+主键` / `ALT+主键`（可叠加，如 `CTRL+SHIFT+K`），
持久化在 `settings.properties` 的 `hotkey.<appId>`。Esc 取消捕获；
重复按同一主键（无修饰键）清除绑定。绑定冲突只警告不阻止。

### 触发行为与 opensInsidePhone()

按键触发时调用你的 `IPhoneApp.onActivate(...)`。默认行为由
`opensInsidePhone()` 决定（默认 `!isDirectAction()`）：

- **页面型 App**（默认）：先打开手机界面，再进入你的页面。
- **直达型 App**（`isDirectAction() == true`）：不开机、不弹界面，直接回调
  `onActivate`——适合传送、拍照等一按即用的动作。若你的 `onActivate`
  自行打开了 GuiScreen，该屏幕会保留。

热键只在无 GUI 打开、玩家存活且非 spectator 时生效；仅支持键盘
（修饰键按物理左右 Shift/Ctrl/Alt 任一即可）。App 被玩家停用后热键不触发。

## 九、渲染安全（GL 状态）

宿主每帧渲染结束后自检 `GL_SCISSOR_TEST`：若你的 App 在页面上开启了 scissor
（或调用了会改 GL 状态的原生渲染代码）却没有恢复，宿主会自动关掉泄漏的裁剪层，
在日志打印 `[mcphone] scissor leak detected on page '...'`，并在设置页显示一次
提示。不会崩溃，但请自觉配对开关。

规则：

1. **scissor/stencil 等状态必须 try/finally 成对恢复**：`glEnable(GL_SCISSOR_TEST)`
   后务必在 finally 里 `glDisable`。优先使用 Qz-UILib 的 `setClipChildren(true)`
   节点裁剪，宿主会自动管理。
2. 不要在 `createPage` / 回调里改投影矩阵、视口或帧缓冲；页面建树是纯场景操作。
3. 自绘纹理/图片：**`PhoneWidgets` 没有 image 构建器**（javap 全量核实），改用 Qz 的
   `node.setImageSource(...)`（`HostImageSource` 系列；用法参考 music 附属 `MusicApp.java:418-421 + 619-621`）；
   绕开宿主直接画 GL 的代码出了问题不会被本 API 兼容性承诺覆盖。
