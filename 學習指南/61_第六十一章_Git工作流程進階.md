# 第六十一章：Git 工作流程進階

## 前言：Git 不只是「儲存程式碼的地方」

很多初學者把 Git 當成「備份工具」：改了程式碼就 `git add . && git commit -m "update"` 然後 push。  
在公司團隊裡，這樣做會讓 code review 變成噩夢，也讓歷史記錄毫無意義。

這章教你在真實工程團隊中使用 Git 的方式。

---

## 一、三種主流 Git 工作流程

### 1. GitFlow（傳統，適合有版本號的產品）

```
              ┌── hotfix/xxx ──┐
              │                ↓
main ─────────●────────────────●──────── (生產環境)
              │                ↑
develop ──────●────●────●──────●──────── (整合測試環境)
              │    ↑    ↑
feature/A ────●────┘    │
feature/B ──────────────┘
```

```
分支說明：
  main (master)：只有生產可用的程式碼，每次合併都對應一個版本號（v1.2.0）
  develop：下一個版本的整合分支，所有 feature 合到這裡
  feature/xxx：每個功能一個分支，從 develop 切出，完成後 PR 回 develop
  release/1.2.0：準備發版時從 develop 切出，只修 bug，完成後同時合回 main 和 develop
  hotfix/xxx：生產緊急 bug，從 main 切出，修完同時合回 main 和 develop

適合：
  - 有固定發版週期的產品（每兩週 release 一次）
  - 需要同時維護多個版本（v1.x 和 v2.x 並行）
  - 桌面軟體、SDK、Library
```

### 2. Trunk-Based Development（現代，適合 CI/CD）

```
main ──●──●──●──●──●──●──  (每個 commit 都可以部署)
       ↑  ↑  ↑  ↑
短命分支（1-2天內合回）
feature/A（2天）
feature/B（1天）
```

```
規則：
  - 只有一個長期分支：main（或 trunk）
  - feature 分支生命週期很短（1-2 天，最多一週）
  - 用 Feature Flag 控制功能可見性（程式碼先合進去，功能還沒開放）
  - CI 必須對每個 commit 跑測試

適合：
  - 需要持續交付（每天部署多次）的 SaaS 產品
  - 大型團隊（100+ 工程師），長命分支會造成整合地獄
  - 有完整 CI/CD 和測試覆蓋率的團隊
```

### 3. GitHub Flow（中間方案，最常見）

```
main ──────────────────────────────── (保護分支，直接部署)
         ↑         ↑          ↑
feature/A  ──────PR──┘
feature/B  ──────────────PR───┘
hotfix/C   ──────────────────────PR──┘
```

```
規則（就這幾條）：
  1. main 分支永遠是可部署的
  2. 從 main 切出 feature 分支，命名清楚（feature/add-payment）
  3. 定期 push 到遠端（讓團隊看到你的進度）
  4. 需要合併時開 Pull Request（PR）
  5. PR 要有 Review，合併前要 CI 通過
  6. 合併後立刻部署

適合：
  - 大多數 Web 應用 / API 服務
  - 中小型團隊（5-50 人）
  - 需要簡單易懂的工作流程
```

---

## 二、高效的 Git 操作技巧

### git rebase：整理 commit 歷史

```bash
# 問題：你的 feature 分支落後 main 10 個 commit，合併後有大量 merge commit
# 解法：rebase 讓你的 commit「接在」main 最新 commit 後面

# 在 feature 分支上：
git fetch origin
git rebase origin/main
# → 你的 commits 被「移植」到 main 最新狀態之後
# → 合併時不會產生 merge commit，歷史是一條直線

# 如果 rebase 過程中有衝突：
git status                # 看哪些檔案有衝突
# 手動解決衝突後：
git add <解決衝突的檔案>
git rebase --continue     # 繼續 rebase
# 不想繼續：
git rebase --abort        # 放棄，回到 rebase 前的狀態
```

### git rebase -i：互動式整理 commit

```bash
# 合 PR 前把散亂的 commit 整理乾淨
git rebase -i HEAD~5  # 整理最近 5 個 commit

# 會開啟編輯器，顯示：
pick abc1234 WIP: 開始做登入功能
pick def5678 fix typo
pick ghi9012 加入 JWT 驗證
pick jkl3456 fix: 修正 token 過期處理
pick mno7890 test: 加入登入測試

# 把 "pick" 改成：
#   reword (r)：保留 commit，但修改訊息
#   squash (s)：合併到前一個 commit（訊息合併）
#   fixup (f)：合併到前一個 commit（丟掉這個 commit 的訊息）
#   drop (d)：刪除這個 commit

# 整理後：
pick abc1234 feat(auth): 實作 JWT 登入功能
f def5678
f ghi9012
f jkl3456
f mno7890
# → 5 個 commit 合成 1 個，PR 審查變得清晰
```

### git cherry-pick：只取特定 commit

```bash
# 場景：hotfix 在 hotfix 分支修好了，也要把這個 fix 加到 develop 分支
git checkout develop
git cherry-pick abc1234  # 把 abc1234 這個 commit 複製到當前分支

# cherry-pick 一個範圍：
git cherry-pick abc1234..def5678  # 複製這個範圍內的 commit

# ⚠️ cherry-pick 會建立新的 commit（不同的 hash），
#    如果之後 hotfix 分支被 merge 進來，可能有重複的變更
```

### git stash：暫存未完成的工作

```bash
# 場景：正在做 feature，突然要切過去修 hotfix
git stash             # 把所有未 commit 的變更存起來（WIP 狀態）
git stash push -m "登入功能 WIP - JWT 驗證到一半"  # 給 stash 加說明

git checkout hotfix/bug-fix   # 切到 hotfix 分支
# 修 bug...
git checkout feature/login    # 切回來
git stash pop                 # 還原暫存的變更

git stash list                # 看所有 stash
git stash drop stash@{0}     # 刪除某個 stash
```

---

## 三、Commit Message 規範（Conventional Commits）

好的 commit message 讓 `git log` 變成有意義的文件：

```
格式：
  <type>(<scope>): <subject>
  空行
  [body]（可選）
  空行
  [footer]（可選，BREAKING CHANGE / Closes #issue）

type 類型：
  feat     新功能
  fix      Bug 修復
  docs     只改了文件
  style    程式碼格式（不影響功能）
  refactor 重構（不是 feat 也不是 fix）
  test     加測試
  chore    改建置工具、CI 設定等
  perf     效能最佳化
  ci       CI/CD 設定變更
```

```bash
# ✅ 好的 commit message
git commit -m "feat(payment): 實作信用卡付款 Stripe 整合

加入 Stripe Webhook 處理付款確認，支援：
- 付款成功 (payment_intent.succeeded)
- 付款失敗 (payment_intent.payment_failed)

Closes #234"

# ✅ 也可以是簡短的一行
git commit -m "fix(order): 修正訂單金額計算精度問題（BigDecimal scale 調整）"
git commit -m "docs(api): 更新 POST /orders 的 request body 文件"
git commit -m "test(auth): 加入 JWT token 過期的整合測試"

# ❌ 差的 commit message
git commit -m "update"
git commit -m "fix bug"
git commit -m "wip"
git commit -m "asdfasdf"
```

### 為什麼 commit message 很重要？

```bash
# 六個月後，某個 Bug 出現，你需要找「是哪個 commit 引入了這個問題」
git log --oneline --since="2024-01-01"

# 好的記錄：
abc1234 feat(order): 加入訂單金額上限驗證（不超過 100 萬）
def5678 fix(payment): 修正 Stripe Webhook 重複處理問題
ghi9012 refactor(inventory): 將庫存扣減改為 Redis Lua 原子操作
jkl3456 test(auth): 加入 JWT 簽名演算法測試

# 差的記錄：
abc1234 update
def5678 fix bug
ghi9012 changes
jkl3456 stuff

# → 差的記錄讓 debug 時間增加 10 倍
```

---

## 四、Pull Request 最佳實踐

### PR 大小控制

```
❌ 一個 PR 改了 50 個檔案
  → Reviewer 看不完
  → Review 流於形式（Everyone rubber-stamps）
  → 一旦有問題，很難 revert

✅ 一個 PR 改 5-15 個檔案（理想）
  → Review 在 30 分鐘內可完成
  → 有問題容易 revert
  → 每個 PR 對應一個清楚的功能單元
```

### PR Description 模板

```markdown
## 這個 PR 做了什麼？
簡短說明這個變更的目的（2-3 句話）

## 為什麼要這樣改？
背景與動機（可選，但有助於 Reviewer 理解設計決策）

## 測試方式
- [ ] 本地測試通過
- [ ] 加入了單元測試（覆蓋 xxx 情境）
- [ ] 已在 staging 環境驗證

## Breaking Changes（如有）
說明 API 不相容的變更

## 相關 Issue
Closes #234
```

---

## 五、保護 main 分支 <!-- 💡 進階 -->

```
GitHub Branch Protection Rules（在 Repository Settings 設定）：

✅ Require pull request reviews before merging
   → 至少要有 1 個（建議 2 個）Reviewer 批准
   
✅ Require status checks to pass before merging
   → CI 必須全綠才能合併（build + test + lint）
   
✅ Require branches to be up to date before merging
   → 合併前必須先 rebase/merge main（避免「在本地跑得過，但合進去就壞了」）
   
✅ Restrict pushes
   → 沒有人可以直接 push 到 main（包括管理員）
```

---

## 六、常見 Git 事故救援

```bash
# 事故 1：commit 了但想撤銷（還沒 push）
git reset --soft HEAD~1   # 撤銷 commit，保留變更在 staging area
git reset --mixed HEAD~1  # 撤銷 commit + unstage，但保留檔案變更（預設）
git reset --hard HEAD~1   # 撤銷 commit + 所有變更都丟棄（危險！）

# 事故 2：已經 push 了，需要撤銷
# ❌ 不要用 git push --force（會讓其他人的 history 出問題）
# ✅ 用 git revert 建立一個「反向 commit」
git revert abc1234        # 建立一個新 commit，效果是撤銷 abc1234 的變更
git push origin main      # 安全推送

# 事故 3：不小心 commit 了密碼/金鑰
# 1. 立刻 rotate（換掉）那個密鑰！（Git 歷史很難完全清除，別指望靠 Git）
# 2. 用 git filter-repo 清除歷史（比 git filter-branch 更快更安全）
pip install git-filter-repo
git filter-repo --path-glob '**/*.env' --invert-paths  # 從歷史刪除 .env 檔案

# 事故 4：rebase 出錯，想回到 rebase 前
git reflog               # 查看所有 HEAD 移動記錄
git reset --hard HEAD@{5}  # 回到 5 步之前的 HEAD

# 事故 5：誤刪了分支
git reflog               # 找到那個分支最後的 commit hash
git checkout -b my-branch abc1234  # 重建分支
```

---

## 七、Git Alias 提升效率

```bash
# 在 ~/.gitconfig 加入常用縮寫
[alias]
    st = status
    co = checkout
    br = branch
    lg = log --oneline --graph --decorate --all
    unstage = reset HEAD --
    last = log -1 HEAD
    aliases = config --get-regexp alias
    # 快速查看最近的 commit
    recent = log --oneline -10
    # 顯示哪些 branch 已合併
    merged = branch --merged main
    # 清理已合併的分支
    cleanup = "!git branch --merged main | grep -v main | xargs git branch -d"
```

```bash
# 效果：
git lg
# 顯示漂亮的 ASCII 圖形 commit 樹

git cleanup
# 一鍵刪除所有已合併到 main 的本地分支
```

---

## 本章練習題

**Q1：git merge 和 git rebase 的主要差異是什麼？各適合什麼情況？**
<details>
<summary>答案</summary>
git merge 保留完整歷史：把兩個分支的 commit 合在一起，產生一個 merge commit，歷史圖形是「菱形」，可以清楚看到什麼時候有 feature 分支被合進來。適合：開源專案（保留貢獻者的原始歷史）、長命的 develop/main 分支間的合併。git rebase 重寫歷史：把你的 commit「移植」到目標分支最新的 commit 之後，歷史是一條直線，看起來像「從未有過分支」。適合：在合 PR 前整理自己的 feature 分支（讓歷史更清晰）、讓 feature 分支跟上 main 的最新狀態。黃金法則：永遠不要 rebase 已經 push 到遠端且其他人正在基於它工作的 commit，因為 rebase 會改變 commit hash，讓其他人的 history 出現分歧。
</details>

**Q2：GitFlow 和 Trunk-Based Development 的核心哲學差異是什麼？**
<details>
<summary>答案</summary>
GitFlow 的哲學是「隔離」：每個功能在自己的分支裡開發完再合，最大化每次合併的穩定性，代價是分支間的整合延遲（Integration Hell）。適合有固定版本的產品。Trunk-Based Development 的哲學是「持續整合」：每個工程師每天至少 push 一次到 main，透過 Feature Flag 控制功能可見性。這樣每次都在整合，所以從不會有「大爆炸式整合」。代價是需要強大的 CI/CD 和測試覆蓋率支撐。現代 SaaS 公司（Google、Facebook、Netflix）幾乎都在用 Trunk-Based，因為它讓「持續交付」成為可能。
</details>

**Q3：同事把含有 API 金鑰的 commit 推上了 GitHub public repo，應該怎麼處理？**
<details>
<summary>答案</summary>
順序很重要，一定要先做第 1 步：Step 1（最緊急！）立刻 rotate（撤銷並重新生成）那個 API 金鑰，讓舊金鑰失效。GitHub 爬蟲在幾分鐘內就會掃到，攻擊者可能已經看到了，所以「把歷史清乾淨」救不了已經洩漏的金鑰。Step 2 從 Git 歷史刪除：用 git filter-repo（不是過時的 filter-branch）把含有金鑰的檔案從所有 commit 裡刪除，然後 force push。Step 3 通知所有 clone 了這個 repo 的人，讓他們重新 clone（他們本地還有舊歷史）。Step 4 檢查是否有異常使用記錄（雲平台的 API usage 日誌）。Step 5 加入 .gitignore，防止再次發生；考慮使用 git-secrets 或 truffleHog 掃描。
</details>
