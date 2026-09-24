# 分辨率与流程识别修复：第四版实测记录

本分支保存已完成一轮用户实机测试的代码，基于 1.0.12 提交
`fb62a5abe2bf9b02e21857eaa37b260e6e7cdae5`。上传时 fork/main 已更新到
1.0.13，本分支尚未合入或验证该版本的其他修改，不应直接视为最新 main 的可合并版本。

## 问题和修改

- 登录连接提示使用宽屏 COVER 坐标，在 1920×1080 下越界，导致正在连接被识别为可点击标题页。增加贴边映射、局部位置/尺寸搜索和低分辨率模板采样；连接状态下等待并保留超时限制。
- 测试应用与原版同时使用 8765，导致控制台可能显示原版空状态。测试构建统一改用 8766，并在初始状态、页面和导出报告中标注版本及包名。
- 角色加入弹窗得分 0.958、背景地图 0.903，未达到全局差值门槛而变成 UNKNOWN。标题和关闭按钮均强匹配且候选为地图/该弹窗时，优先识别弹窗；保留其他页面差值规则。
- 第 26/36 个节点的 WIN 页面“下一步”得分约 0.797～0.798，低于 0.80，随后等待五分钟超时。为两种已有按钮位置增加局部匹配搜索，沿用最佳匹配矩形点击，不降低阈值。

## 验证

第四版 APK 构建及签名校验通过；针对本次修改的七个测试类共 100 项，68 通过、0 失败、32 跳过。
跳过项属于原有 `LabyrinthEntryFrameProcessorTest`，原因是缺少历史图片，不能计为通过。
没有声称全量单元测试或 GitHub CI 通过。

真实 WIN 截图回归：

| 尺寸 | 下一步得分 | 页面 |
|---|---:|---|
| 1280×720 | 0.885 | BATTLE_RESULT |
| 1920×1080 | 0.909 | BATTLE_RESULT |
| 2560×1440 | 0.844 | BATTLE_RESULT |

1440p 是现场图，另两种是同图缩放。另有真实连接页、角色加入弹窗、标题页及合成布局测试。
回归图片位于 `app/src/test/resources/labyrinth/resolution-20260923/`，来源及脱敏说明见该目录 README。
未上传原始 debug 压缩包、账号凭据、签名私钥或本地工具链。

第四版已在用户 MuMu 上覆盖安装，用户反馈“一轮测试已经通过”。这不代表所有设备、分辨率或流程均已验收。
公会选择页的问题尚未捕获现场并确认修复；真实宽屏布局和最新 1.0.13 的兼容性仍待验证。

## 构建和复核

从 android 目录执行（需要项目对应的 JDK / Android SDK）：

```sh
./gradlew -PresolutionTest=true :app:testDebugUnitTest --tests '*ResolutionEntryRegressionTest' --tests '*ReferenceFitMapperTest' --tests '*LabyrinthEntryPageClassifierTest' --tests '*LabyrinthEntryActionPlannerTest' --tests '*LabyrinthFallbackTapsTest' --tests '*LabyrinthEntryFrameProcessorTest' --tests '*AppVersionTest' :app:assembleDebug
```

测试包名为 `com.landosol.toolbox.resolutiontest`，版本为 `1.0.12-resolution-test.4`，可与原版并存。
实测 APK SHA-256：

```text
f99a86492152939bc45159943e3bde6adffd6ae25ca58a165c4707ec4f519470
```

再次构建的 APK 可能因签名或构建环境不同而具有不同哈希。
