# 仓库协作约定

## 1. 分支与 Pull Request

- 默认分支是 `main`，禁止直接推送；所有代码、文档和构建配置变更均通过 PR 合入。
- 新功能、文档和协作流程使用 `feature/<名称>`；缺陷修复使用 `fixbug/<名称>`。
- 从最新的 `origin/main` 创建工作分支，PR 的目标分支为 `main`。
- 不再创建或更新 `codex/*`、`master` 等旧命名分支；已有历史分支保留供追溯。
- PR 必须通过 `Validate branch policy` 和 `Build and verify installable ZIP`。
- 管理员同样遵守规则，不绕过保护或临时关闭规则直接推送。

## 2. 构建与发布

- `feature/*`、`fixbug/*` 的推送和面向 `main` 的 PR 会触发构建；功能分支不发布 Release。
- 分支 Push、PR 合并到 `main`、以及在分支手动运行工作流，都只做构建和运行验证，不上传正式安装包、不生成源码分发包、不创建 Release。
- 需要发布插件新版本时，在 PR 中递增 `plugin/plugin.xml` 的版本号；合入 `main` 后，在明确发版时创建并推送 `v<插件版本>` tag。不得因为 PR 合并而自动创建 tag。
- 只有已存在、版本匹配且指向 `main` 已合入提交的 tag 才允许打包分发和发布 Release；发布脚本不得隐式创建或移动 tag。
- 已发布的同版本附件不覆盖。分支保护配置保存在 `.github/rulesets/`，实际 GitHub 规则需同步维护。
- 构建、安装和运行检查方法见 `README.md`、`docs/CI.md`。
