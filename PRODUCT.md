# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

个人及家庭用户自行部署服务端（Cloudflare Workers 或 Docker 自托管）后，在 Android 手机上使用该 App 查看/操作自己的数据。产品公开分发（GitHub Release / Play），实际使用者是自托管站长本人及其家人，但图标面向陌生用户的桌面竞争环境设计。

## Product Purpose

「cron_day_report」服务端是多模块个人生活面板（网站监控、基金持仓、体重记录、资产月报、待办清单）+ 定时推送到企业微信/Webhook/邮件。Android App 是它的移动入口：待办清单的桌面小组件（Glance App Widget，可勾选、新增、折叠）+ 内嵌 WebView 壳访问基金/体重/资产等网页模块。

产品核心定位（用户原话）：「生活的缝缝补补」——把生活里零散需要打理的小事收集、提醒、补齐；待办只是排第一位的模块，不是全部。

成功 = 用户打开手机第一眼（桌面小组件）就能看到今天需要缝补的生活事项，并在几秒内处理掉。

## Positioning

与主流待办 App 的差异：本产品不是独立待办工具，而是一个自托管的全家生活操作系统（数据自己掌控、多模块、家人免密协作），App 图标是这套自托管系统的入口，而非又一个待办应用。

## Operating Context

- 服务端自部署（Cloudflare Workers 或 Docker），App 通过 base_url 连接自己的服务器，默认 `https://cron.10023456.xyz`
- 桌面小组件是最高频界面：白底卡片、深色模式 `#1E1B2A` 底
- 公开分发渠道：GitHub Actions CI 自动构建 APK（pre-release）

## Capabilities and Constraints

- Android 独立项目：包名 `xyz.a10023456.todowidget`，minSdk 26，adaptive icon（vector XML，无位图资产）
- 图标现状：紫底 `#A855F7` + 白色圆角卡 + 三条紫线（通用清单隐喻，辨识度弱）
- CI 环境变量注入版本号；release 签名回退 debug

## Brand Commitments

- 品牌紫色 `#A855F7`（配 `brand_dark #9333EA`）是保留的品牌承诺，贯穿 App 主题、小组件、下拉刷新
- App 显示名「待办清单」，小组件名「待办速览」
- 图标语义不限于待办勾选，可表达「生活面板/缝缝补补」的整体概念

## Evidence on Hand

- `android/app/src/main/res/drawable/ic_launcher_{background,foreground}.xml`（现行图标 vector）
- `android/app/src/main/res/values/colors.xml`（全部品牌色板）
- 服务端 `src/web/layout.js` 网站视觉（同紫色系）
- 无 logo 设计稿、无真实用户证言，不可虚构

## Product Principles

1. 自托管个人生活系统的入口，不是通用待办工具
2. 桌面小组件体验优先——图标与小组件视觉应同族
3. 生活的缝缝补补：温和、日常、贴身，而非生产力焦虑感
4. 品牌紫是贯穿全端唯一的色彩承诺

## Accessibility & Inclusion

图标需在深色与浅色壁纸下均可辨识；前景与背景对比度足够（adaptive icon 安全区内）。
