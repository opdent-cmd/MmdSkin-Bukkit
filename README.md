# MmdSkin-Bukkit

**MmdSkin-Bukkit** 是 [MC-MMD-rust](https://github.com/shiroha/MC-MMD-rust) 模组的 Bukkit/Spigot 插件端实现。

它负责在插件服务端上为玩家之间同步 MMD 模型、动画、物理状态和表情，使得安装了模组的玩家能够在插件服上互相看到对方的 MMD 形象和动作。

## 功能特性

- **模型同步**: 自动同步玩家选择的 MMD 模型给其他玩家。
- **跨版本兼容**: 支持 Minecraft 1.20.1 和 1.21.1 客户端（通过 ViaVersion 等跨版本插件连接时），无论客户端版本如何，只要安装了对应版本的 MC-MMD-rust 模组，均相互可见。
- **动画转发**: 实时转发动作播放（如跳舞、打招呼）、物理重置等指令。
- **表情同步**: 支持同步自定义表情（Morph）。
- **无依赖**: 纯 Bukkit API 实现，支持 Spigot, Paper, Purpur, Leaf, Gale 等所有衍生核心。

## 安装

1.从 [Releases](https://github.com/opdent-cmd/MmdSkin-Bukkit/releases) 页面下载最新版本的插件构建。

2.放入服务端的 `plugins` 文件夹中。

3.重启服务器

> [!IMPORTANT]
> 这是一个服务端插件，客户端不需要也不能进行安装。客户端仍需安装相应的原版 Fabric/Forge/NeoForge 版 MC-MMD-rust 模组。

## 构建

本项目使用 Gradle 构建。

```bash
# 构建插件
./gradlew build
```

## 许可证

MIT License
