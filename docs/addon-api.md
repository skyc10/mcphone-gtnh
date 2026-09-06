# MCphone 附属 App 开发指南（GTNH 1.7.10 版）

## 注册方式（二选一）

1. **代码注册**：在你的 mod 初始化阶段调用：

```java
com.november.mcphone.api.PhoneApi.register(myApp);
```

2. **ServiceLoader 自动发现**：在你的 mod jar 里放
   `META-INF/services/com.november.mcphone.api.IPhoneApp`，
   内容为实现类的全限定名。MCphone 在 postInit 阶段扫描。

## 实现接口

```java
public class MyCoolApp implements IPhoneApp {
    public String id() { return "mycoolapp"; }
    public String displayName() { return "我的应用"; }
    public int iconColor() { return 0xFF8E4E9E; }
    public boolean isBuiltin() { return false; }

    public void renderIcon(int x, int y, int size) {
        // GL11 立即模式画图标；x/y 为左上角，size 约 18px
        com.november.mcphone.client.UiHelper.rect(x + 2, y + 2, size - 4, size - 4, 0xFF334455);
        net.minecraft.client.Minecraft.getMinecraft().fontRendererObj
            .drawStringWithShadow("我", x + 5, y + 5, 0xFFFFFFFF);
    }

    public AppScreen createScreen(PhoneGui gui) {
        return new MyCoolScreen(gui);
    }
}
```

`AppScreen` 需要实现 `render(sx, sy, sw, sh, mx, my, partialTicks)`，
可选实现鼠标点击、按键、每 tick 更新。坐标均为屏幕绝对坐标，
裁剪（超出手机屏幕的部分）由框架负责。

## 注意事项

- App 是**纯客户端**对象；需要服务端行为时用你自己的网络包，
  或复用 `com.november.mcphone.net.NetworkHandler.sendToServer`。
- 用户可以在手机"应用管理"里关闭任何 App（含外部 App）。
- 存储请使用自己的目录；MCphone 自用目录为 `.minecraft/mcphone/`。
