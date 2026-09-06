# MCphone 附属 App 开发指南（SPI）

MCphone 提供基于 Qz-UILib 场景渲染的 App 扩展接口。附属 mod 只依赖
`com.november.mcphone.api` 包即可开发 App，无需接触 MCphone 内部实现。

## 一、注册方式

两种（任选）：

1. **代码注册**：你的 mod 在客户端初始化阶段调用
   `PhoneApi.register(new MyApp())`。
2. **ServiceLoader 自动发现**：在你的 jar 里放
   `META-INF/services/com.november.mcphone.api.IPhoneApp`，内容为实现类全限定名。
   MCphone 在 postInit 阶段收集。id 冲突时先注册者优先。

## 二、核心类型

| 类型 | 作用 |
|------|------|
| `IPhoneApp` | App 接口：id/名称/图标/页面或直达动作 |
| `PhoneAppBase` | 便捷抽象基类（推荐继承） |
| `PhoneContext` | 运行上下文：场景、导航、Toast、网络、传送点 |
| `PhoneWidgets` | 常用控件构建器（文本/按钮/卡片/信息行…） |
| `PhoneAppConfig` | 每 App 持久化 KV 配置（跨存档） |
| `PhoneApi` | 注册表：register / byId / apps / orderedApps |

### App 两种形态

- **页面型**（默认）：点击图标后 `createPage(ctx)` 构建页面场景树。
- **直达型**（`isDirectAction() = true`）：点击图标立即执行 `onActivate(ctx, shift)`，
  不打开页面（如拍照、开末影箱）。
- 页面型额外支持 `onShiftActivate(ctx)`：Shift+点击图标的快捷动作
  （内建传送用它实现"快速绑定当前位置"）。

### 图标（三选一，优先级从高到低）

1. `iconTexture()` —— 纹理路径（推荐）：`"mymod:textures/ui/myapp.png"`，
   按 128x128 解析，放在 `assets/mymod/textures/ui/`。
2. `iconItem()` —— 物品图标（`new ItemStack(...)`）。
3. `iconGlyph()` —— 1-2 个字符画在圆角底板上。

底板颜色：`iconColor()`（ARGB）。

## 三、最小示例（页面型）

```java
import com.november.mcphone.api.*;

public final class MyStatusApp extends PhoneAppBase {

    public MyStatusApp() {
        super("mystatus", "app.mymod.status", 0xFF4E8E5E); // id / 名称语言键 / 底色
    }

    @Override
    public SceneNode createPage(PhoneContext ctx) {
        SceneNode page = PhoneWidgets.scrollColumn(ctx);
        page.appendChild(PhoneWidgets.title(ctx, ctx.tr("app.mymod.status")));
        page.appendChild(PhoneWidgets.infoRow(ctx, "生物群系", biomeName));
        PhoneWidgets.primaryButton(ctx, page, "刷新", () -> {
            // 按钮回调已自动进入延迟队列，在这里改树/挂载是安全的
            ctx.toast("已刷新");
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
    public void onActivate(PhoneContext ctx, boolean shift) {
        ctx.closePhone();
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
| `scaledFont(int)` | 按用户字体缩放换算字号（**页面文字一律用它**） |
| `tr(key)` | 本地化 |
| `clock()` | 世界时钟 Signal（可 `runtime().bindText(node, ctx.clock())`） |
| `openApp(id) / backHome() / isHome() / closePhone()` | 导航 |
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

## 六、图标位置

玩家在手机"应用管理"里用 ↑/↓ 调整主屏图标顺序，持久化在
`.minecraft/mcphone/settings.properties` 的 `appOrder`。
附属 App 注册后默认排在最后，玩家可自行上移。

## 七、注意事项

1. **回调里改树必须包 `ctx.post(...)`**：Qz-UILib 输入路由在迭代中派发事件，
   同步 mount/dispose 会抛 ConcurrentModificationException。
   `PhoneWidgets.button / primaryButton / onClick` 已自动包装。
2. 页面构建是**一次性建树**：动态外观用 `ctx.runtime().bind(signal, ...)` 派生，
   不要在树里轮询世界状态。
3. 文本输入是受控组件：`onChange` 里必须把值写回你的 `Signal`，保存时从 Signal 读。
4. 联网操作用你自己的网络包（`ctx.sendToServer` 仅能发 MCphone 通道已注册的消息）。
5. App 全部是客户端对象；服务端逻辑写在你的 mod 里。
6. 设置页调整界面/字体缩放会重建页面——不要跨重建缓存场景节点引用。
