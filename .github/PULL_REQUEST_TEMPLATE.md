## 这个 PR 做了什么

<!-- 一句话说明目的，并关联相关 Issue，例如 Closes #12 -->

## 类型

- [ ] Bug 修复
- [ ] 新功能
- [ ] 文档
- [ ] 重构 / 清理（不改变行为）
- [ ] 其他：

## 改动范围

<!-- 列出主要修改的文件或模块 -->

## 验证结果

<!-- 写清实际运行了哪些命令、结果如何。没有运行的检查请明确说明。 -->

```bash
# 后端（从仓库根目录，先按 README 准备自己的 .env）
docker compose -f docker-compose.example.yml config --quiet
python3 -m unittest discover -s backend

# Android（从 HADashboard/ 执行）
bash ./gradlew --no-daemon :app:testDebugUnitTest
```

## 提交前自查

- [ ] 改动聚焦，没有顺带重构无关部分
- [ ] 已保留他人未提交的修改
- [ ] 未包含 `.env`、keystore、APK、备份、运行数据或任何真实凭据
- [ ] 未包含真实域名、IP、Token、密码、账号昵称或用户 ID
- [ ] 修改配置、接口或部署行为时，已同步更新 README / 文档
- [ ] 新增可选集成时，服务缺失能合理降级，不引入隐式挂载
- [ ] 涉及的测试实际运行过，且结果已如实填写

## 备注

<!-- 已知限制、后续计划、需要维护者注意的地方。可选。 -->
