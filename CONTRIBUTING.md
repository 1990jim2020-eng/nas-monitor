# 贡献指南

欢迎通过 Issue 报告问题，通过 Pull Request 提交改进。提交前说明问题、复现步骤、预期行为和验证结果；截图、日志和配置请脱敏，不包含真实域名、IP、Token、密码或账号文件。

## 开发环境

- 后端：Python 3.11，依赖安装命令 `python3 -m pip install -r backend/requirements.txt`；入口 `backend/api_server.py`。推荐按 [README.md](README.md) 用仓库根目录 Compose 模板验收，避免复用个人环境部署文件。
- Android：JDK 17、Android SDK 34 / Build Tools 34.0.0，使用 `HADashboard/gradlew`，不要求全局安装 Gradle。
- 首次贡献先读 README 中的配置与可选服务说明。新增集成应能在服务缺失时合理处理，并避免新的个人地址、凭据和隐式挂载。

## 提交范围与验证

保持改动聚焦，保留他人的未提交修改。修改配置、接口或部署行为时同步更新 README，并在本地开发记录中记录原因、改动范围、运行验证、交付位置和未验证事项。

按改动范围运行适用检查，例如：

```bash
# 从仓库根目录，先按 README 准备自己的 .env
docker compose -f docker-compose.example.yml config --quiet
docker compose -f docker-compose.example.yml build
docker compose -f docker-compose.example.yml up -d
curl --fail 'http://<你的NAS地址>:8765/health'

# Android，从 HADashboard/ 执行
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

后端单测位于 `backend/test_*.py`，优先运行受改动影响的测试；使用独立、脱敏的测试目录与配置，避免测试写入生产 `data/`、快照或启动真实采集任务。记录实际运行命令和结果，不能把语法检查写成端到端验证。

不要提交 `.env`、keystore、APK、备份和运行数据。`.gitignore` 不会移除已经被 Git 跟踪的敏感文件；发布前由维护者确认仓库范围及已有跟踪内容。

## Pull Request

说明触发场景、修改后行为、验证结果与仍有的限制。涉及 UI 时附脱敏截图；涉及可选服务时注明上游前提。贡献按 [MIT License](LICENSE) 发布。

## 发布维护

维护者配置四个 Android 签名 Secrets，保证连续版本使用同一 keystore 且递增 `versionCode`，再推送版本 tag。两个 GitHub Actions 会分别发布签名 APK 和 GHCR 后端镜像；推送 `main` 只触发后端镜像发布。未执行实际 Actions 时，不宣称已验证 GitHub 发布或多架构镜像。
