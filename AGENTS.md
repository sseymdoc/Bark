# Bark（android 分支）

本分支只保留 Bark 的 Android 客户端，代码都在 `android/` 下，构建与测试见 `android/README.md`。

## Git 提交与推送

- 提交默认只留在本地：`git commit` 之后不要自行 `git push`；只有要开 PR 时，才按下一条把 PR 分支推到个人仓库。
- 改动要进入个人仓库 `github.com/sseymdoc/Bark` 必须开 PR：把改动推到个人仓库的一个 PR 分支，再开 PR 合入目标分支；不直接推送到 `android` 等被合入的分支。
  - 用 `gh pr create` 时显式写 `--repo sseymdoc/Bark`：在 fork 仓库里它默认把上游当作 PR 目标。
- 永不主动推送到上游仓库 `github.com/Finb/Bark`，不向它推送任何分支或标签。
- 远端名称因检出而异（本机检出里上游叫 `origin`、个人仓库叫 `fork`），推送前按 URL 判断目标。
