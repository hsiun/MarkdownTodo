# MarkdownTodo - 基于Markdown的安卓待办应用

一个功能完善的Android待办事项和笔记管理应用，支持Markdown格式，并可通过Git进行云端同步。

## 功能特性

### 📝 待办事项管理
- ✅ 创建、编辑、删除待办事项
- ✅ 标记完成/未完成状态
- ✅ 支持多个待办列表
- ✅ 设置提醒（支持重复提醒）
- ✅ 显示/隐藏已完成待办
- ✅ 左滑删除待办事项

### 📄 笔记管理
- ✅ 创建、编辑、删除笔记
- ✅ 支持Markdown格式
- ✅ 自动保存创建时间和更新时间
- ✅ UUID唯一标识，确保数据一致性

### ☁️ Git同步
- ✅ 通过GitHub仓库进行云端同步
- ✅ 自动推送本地更改
- ✅ 下拉刷新同步云端数据
- ✅ 自动处理同步冲突
- ✅ 支持自动同步设置

### ⚙️ 设置功能
- ✅ Git仓库配置（URL和Token）
- ✅ 显示设置（显示/隐藏已完成待办）
- ✅ 同步设置（自动同步、同步间隔）
- ✅ 主题颜色设置
- ✅ 通知设置（启用/禁用、震动）
- ✅ 排序方式设置

### 🔔 提醒功能
- ✅ 设置待办提醒时间
- ✅ 支持重复提醒（每日、每周、每月）
- ✅ 系统通知提醒
- ✅ 开机自动重新调度提醒

## 技术栈

- **语言**: Kotlin
- **最低SDK**: Android 7.0 (API 24)
- **目标SDK**: Android 14 (API 36)
- **架构**: MVVM模式（使用Manager类管理数据）
- **UI**: Material Design Components
- **数据存储**: 文件系统（Markdown格式）
- **版本控制**: JGit（Git操作）
- **同步**: GitHub仓库

## 项目结构

```
app/src/main/java/com/hsiun/markdowntodo/
├── MainActivity.kt              # 主活动，管理页面切换和协调各组件
├── TodoFragment.kt              # 待办事项Fragment
├── NoteFragment.kt              # 笔记Fragment
├── NoteEditActivity.kt          # 笔记编辑Activity
├── TodoManager.kt               # 待办事项管理器
├── NoteManager.kt               # 笔记管理器
├── TodoListManager.kt           # 待办列表管理器
├── SyncManager.kt               # 同步管理器
├── GitManager.kt                # Git操作管理器
├── SettingsManager.kt           # 设置管理器
├── ReminderScheduler.kt         # 提醒调度器
├── TodoReminderReceiver.kt      # 提醒广播接收器
├── TodoItem.kt                  # 待办事项数据类
├── NoteItem.kt                  # 笔记数据类
├── TodoList.kt                  # 待办列表数据类
└── ...                          # 其他辅助类
```

## 数据格式

### 待办事项格式
待办事项以Markdown格式存储在 `git_repo/todo_lists/` 目录下：

```markdown
- [ ] 待办事项标题
- [x] 已完成待办事项
```

### 笔记格式
笔记以Markdown格式存储在 `git_repo/notes/` 目录下：

```markdown
<!-- UUID: 唯一标识符 -->
# 笔记标题
> 创建时间: 2024-01-01 12:00:00 | 更新时间: 2024-01-01 12:00:00
---
笔记内容
---
```

## 使用说明

### 初始设置

1. **配置Git同步**（可选）
   - 打开应用设置
   - 输入GitHub仓库URL（例如：`https://github.com/username/repo.git`）
   - 输入GitHub Personal Access Token
   - 保存设置

2. **创建待办列表**
   - 在待办页面，点击顶部的列表选择器
   - 选择"新建列表"
   - 输入列表名称

### 基本操作

#### 待办事项
- **添加待办**: 点击右下角浮动按钮（FAB）
- **编辑待办**: 点击待办项
- **完成待办**: 点击待办项前的复选框
- **删除待办**: 左滑待办项或点击删除按钮
- **设置提醒**: 在编辑对话框中设置提醒时间

#### 笔记
- **添加笔记**: 切换到笔记页面，点击FAB
- **编辑笔记**: 点击笔记项
- **删除笔记**: 长按笔记项或点击删除按钮

#### 同步
- **手动同步**: 下拉刷新页面
- **自动同步**: 在设置中启用自动同步

## 开发说明

### 构建项目

1. 克隆项目到本地
2. 使用Android Studio打开项目
3. 等待Gradle同步完成
4. 运行项目

### 依赖库

主要依赖库：
- `androidx.core:core-ktx`
- `androidx.appcompat:appcompat`
- `com.google.android.material:material`
- `androidx.recyclerview:recyclerview`
- `androidx.lifecycle:lifecycle-runtime-ktx`
- `org.eclipse.jgit:org.eclipse.jgit`
- `androidx.swiperefreshlayout:swiperefreshlayout`
- `androidx.viewpager2:viewpager2`

### 权限说明

应用需要以下权限：
- `INTERNET`: 用于Git同步
- `ACCESS_NETWORK_STATE`: 检查网络状态
- `SCHEDULE_EXACT_ALARM`: 精确提醒
- `VIBRATE`: 震动提醒
- `POST_NOTIFICATIONS`: 显示通知

## 注意事项

1. **Git同步**: 首次使用需要配置GitHub仓库和Token
2. **数据备份**: 数据存储在应用私有目录，建议定期同步到Git
3. **提醒功能**: 需要授予精确提醒权限（Android 12+）
4. **网络要求**: 同步功能需要网络连接

## 版本历史

### v1.0
- 初始版本
- 基本的待办和笔记功能
- Git同步支持
- 提醒功能

## 许可证

本项目采用开源许可证，详见LICENSE文件。

## 贡献

欢迎提交Issue和Pull Request！

## 联系方式

如有问题或建议，请通过GitHub Issues联系。
