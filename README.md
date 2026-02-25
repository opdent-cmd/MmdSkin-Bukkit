# MmdSkin-Bukkit

**MmdSkin-Bukkit** 是 [MC-MMD-rust](https://github.com/shiroha/MC-MMD-rust) 模组的 Bukkit/Spigot 服务端插件端口。

它作为一个轻量级的服务端协调器，负责在玩家之间同步 MMD 模型、动画、物理状态和表情，使得安装了客户端模组的玩家能够互相看到对方的 MMD 形象和动作。

## 功能特性

- **模型同步**: 自动同步玩家选择的 MMD 模型给其他玩家。
- **跨版本兼容**: 支持 Minecraft 1.20.1 和 1.21.1 客户端（通过 ViaVersion 等跨版本插件连接时），无论客户端版本如何，只要安装了对应版本的 MC-MMD-rust 模组，均可互相可见。
- **动画转发**: 实时转发动作播放（如跳舞、打招呼）、物理重置等指令。
- **表情同步**: 支持同步自定义表情（Morph）。
- **无依赖**: 纯 Bukkit API 实现，支持 Spigot, Paper, Purpur, Leaf, Gale 等所有衍生核心。

## 安装

1. 下载最新版本的 `MmdSkin-Bukkit-x.x.x.jar`。
2. 将其放入服务端的 `plugins` 文件夹中。
3. 重启服务器。

> **注意**: 这是一个服务端插件，不需要在客户端安装。客户端仍需安装原本的 Fabric/Forge 版 MC-MMD-rust 模组，插件基于1.21.4开发。

## 构建

本项目使用 Gradle 构建。

```bash
# 构建插件
./gradlew build
```

构建产物将位于 `build/libs/` 目录下。

## 协议说明

插件通过 `mmdskin:network` 插件消息通道与客户端通信。它处理以下 OpCode：

- `1`: 动画播放 (C2S -> S2C Broadcast)
- `2`: 物理重置 (C2S -> S2C Broadcast)
- `3`: 模型选择同步 (C2S -> Store & Broadcast)
- `4`: 女仆模型变更 (C2S -> S2C Broadcast)
- `5`: 女仆动画变更 (C2S -> S2C Broadcast)
- `6`: 表情同步 (C2S -> S2C Broadcast)
- `7`: 舞台动画 (C2S -> S2C Broadcast)

插件会自动维护玩家的模型状态，并在新玩家加入时自动下发当前所有在线玩家的模型信息。

## 许可证

MIT License
