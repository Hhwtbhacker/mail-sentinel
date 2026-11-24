# 邮箱总管 Mail Manager

多邮箱定时阅览提醒与审阅后发信：FastAPI、SQLite、LangChain/DeepSeek 和原生 Android APK。服务器约每 5 秒查看是否有新邮件，QQ IMAP 连接保持复用；新邮件才会进入处理流程。系统读取有限正文来识别验证码，并在提醒总结中显示验证码，不发送给 DeepSeek。其他邮件仅在用户开启智能总结后才把最多 2000 字正文片段交给 DeepSeek；原始正文与附件不保存。发信需要用户审阅草稿并确认。DeepSeek API Key、QQ 邮箱 IMAP/SMTP 授权码、Google/Microsoft 刷新令牌使用 Fernet 在数据库中加密；网站登录密码使用 Argon2 哈希。

## 当前功能

- 个人账号注册、登录；多个邮箱与提醒隔离。
- QQ 邮箱使用 IMAP 专用授权码。Gmail、Outlook 使用 OAuth 2.0 与 IMAP XOAUTH2。
- 服务器约每 5 秒检查新邮件（包括已读），连接失败后约 5 秒重试。已处理邮件 UID 去重。
- LangChain create_agent + DeepSeek deepseek-flash，只有 record_reminder 工具。系统提示词在 app/main.py 的 SYSTEM_PROMPT 中。
- 所有新邮件先生成提醒；未配置 DeepSeek 时保留普通提醒。
- 智能总结需在设置中另行开启，仅影响之后发现的新邮件。提醒中的“打开邮箱查找”跳转到原邮箱；IMAP UID 不能可靠映射到网页版单封邮件地址，可复制主题定位。
- 网页推送需要 VAPID。安卓 App 打开时约每 5 秒同步新提醒，只显示普通通知栏消息；关闭后使用系统约 15 分钟的非精确定时检查，可能延迟。

## 本地运行

需要 Python 3.12。

    cd mail-sentinel
    python3 -m venv .venv
    .venv/bin/pip install -r requirements.txt
    cp .env.example .env
    .venv/bin/python scripts/generate_keys.py

把生成的密钥输出填入 .env，本地设置 PUBLIC_ORIGIN=http://localhost:8000、COOKIE_SECURE=false，运行：

    set -a; source .env; set +a
    .venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000

访问 http://localhost:8000。本地 HTTP 不支持普通浏览器的后台推送，生产环境需 HTTPS。

## 当前 Rocky Linux 部署

已部署在 https://1.95.120.199 ，源码目录为 /home/hh/mail-sentinel。服务采用 Python 3.12 虚拟环境和 Caddy 2.11.9，由 systemd 管理：

    sudo systemctl status mail-sentinel.service caddy-mail-sentinel.service
    sudo journalctl -u mail-sentinel.service -f
    sudo journalctl -u caddy-mail-sentinel.service -f

更新代码后重启应用服务：

    cd /home/hh/mail-sentinel
    .venv/bin/pip install -r requirements.txt
    sudo systemctl restart mail-sentinel.service

服务单元模板在 deploy/。Caddy 为该公网 IP 使用 Let's Encrypt shortlived 证书，并自动续签。保持 80 和 443 端口开放，勿删除 /home/hh/.local/share/caddy 内的证书状态。

## Docker Compose 备用部署方式

1. 在服务器安装 Docker Engine 与 Compose 插件，开放 TCP 80、443（云平台安全组和防火墙均需允许）。Rocky Linux 官方说明：https://docs.rockylinux.org/gemstones/containers/docker/
2. 将整个目录上传到服务器，例如 /opt/mail-sentinel，不上传本机 .venv、data、.env 和 Android 构建缓存。
3. 复制 .env.example 为 .env，运行 python3 scripts/generate_keys.py 生成密钥并填入 .env。将 SITE_DOMAIN=1.95.120.199、PUBLIC_ORIGIN=https://1.95.120.199 设置一致。执行 chmod 600 .env。
4. 在能够访问 Docker Hub 或有可靠镜像仓库的环境运行 docker compose up -d --build，再用 docker compose logs -f caddy app 查看 HTTPS 证书与应用启动。用 curl https://1.95.120.199/health 验证。

Let's Encrypt 现在提供 6 天有效期的 IP 地址证书；本项目的 Caddy 配置使用 shortlived ACME profile，并持久化证书数据以便自动续签。首次签发需要该公网 IP 的 80/443 端口可从互联网访问。若以后有域名，把 SITE_DOMAIN 和 PUBLIC_ORIGIN 改为域名即可。说明：https://letsencrypt.org/2026/01/15/6day-and-ip-general-availability/

备份 data/app.db、.env 及 caddy_data 卷。遗失 DATA_ENCRYPTION_KEY 后，已存邮箱授权和 API Key 无法解密。

## OAuth 配置

Google Cloud Console 创建 Web OAuth 客户端，授权重定向 URI 设为 https://1.95.120.199/api/oauth/google/callback，将 ID/Secret 填入 .env。请求 https://mail.google.com/ 范围与离线访问；公开给其他用户时可能需要 Google 应用验证。

Microsoft Entra 应用注册需要允许个人 Microsoft 账号或目标组织账号，添加 Exchange Online 委托权限 IMAP.AccessAsUser.All，授权重定向 URI 为 https://1.95.120.199/api/oauth/microsoft/callback，将 ID/Secret 填入 .env。应用还需 offline_access 以续签令牌。应用注册设置和租户策略可能影响是否能连接。

Google 文档：https://developers.google.com/workspace/gmail/imap/xoauth2-protocol
Microsoft 文档：https://learn.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth/

## Android APK

调试安装包生成位置：android/app/build/outputs/apk/debug/app-debug.apk。它是独立原生应用，可登录、添加 QQ 邮箱、设置 API Key、查看提醒、发系统通知。Gmail/Outlook 按钮打开系统浏览器完成 OAuth 授权。默认服务器地址为 https://1.95.120.199，登录页可修改。调试包可直接安装测试；正式发布需要自己的签名密钥。

从 0.2.0 版起，App 启动时自动读取 /assets/update.json，也可以点“检查 App 更新”。如有更高版本，会从同一 HTTPS 服务器下载 APK、核对 SHA-256、包名和版本号，再打开安卓系统安装界面。首次使用此功能需要手动安装 0.2.0 或更新版本一次；安卓可能要求允许邮箱总管安装未知来源应用，且每次安装都要用户确认，不会静默更新。

0.4.0 版新增安卓顶部横幅提醒。用户需要允许通知，并保持“重要邮件顶部提醒”频道处于高重要性；系统会按非精确的约 15 分钟周期检查，省电策略可能延迟提醒。服务端仅将最近 48 小时内收到且发出的未读邮件交给 Agent；历史未读邮件会标记为已检查，不再生成新提醒。已有提醒记录仍保留在列表中。

0.5.0 版开始按邮件逐封调用 DeepSeek，并保存接口返回的输入、输出、总 token 数。网页和安卓 App 展示累计、北京时间当天和每封邮件的用量，包含 Agent 忽略而未生成提醒的邮件。统计仅覆盖功能上线后的成功调用；旧用量无法追溯，接口未返回用量时会显示“用量未返回”。逐封调用可能比以前的批量调用消耗更多 token。

0.6.0 版新增网页和安卓聊天入口。用户可选择 DeepSeek Flash 或 V4 Pro，以及关闭、低、高、最高四档思考强度。聊天 Agent 仅可使用当前用户的提醒、邮箱检查状态和 token 统计三个只读工具；聊天记录按用户隔离，最近 12 条消息作为上下文。聊天 token 计入累计和今日用量，单封邮件用量列表仍只显示邮件 Agent 调用。使用前需配置 DeepSeek API Key。

0.7.0 版聊天记录改为最新在上，并新增邮件撰写：用户选择已连接的 QQ 发件箱、收件人和写作要求，Agent 生成纯文本草稿，用户可编辑主题、收件人和正文后点击确认发送。发送使用 QQ SMTP SSL 465 和该邮箱的授权码；请在 QQ 邮箱开启 IMAP/SMTP 服务。当前只支持单个收件人、无附件，每用户每小时最多发送 10 封。请求标识阻止重复提交，发送日志只保存发件箱 ID、收件人、主题、状态和时间，不保存已发送正文。草稿生成 token 计入累计和今日统计。

0.7.1 版修正聊天展示顺序：网页按时间正序显示，一问一答，新消息在底部；安卓按问答轮次倒序显示，最新一轮在顶部，但每轮仍先问后答。服务端存储与 Agent 上下文顺序不变。

0.8.0 版把服务器邮箱发现周期改为约 5 秒，QQ IMAP 连接复用，仅新邮件进入 Agent。验证码邮件优先用本地规则提取验证码并放入总结，不调用模型；提取不确定时提示打开原邮箱核实。安卓实时提醒服务开启时约每 5 秒同步，显示顶部横幅和常驻运行状态通知；Android 的电池优化与后台限制可能影响实际时效。网页提醒栏每 5 秒刷新。

0.8.1 版补充“登录代码”等验证码主题识别，并回填近期历史验证码邮件的总结（标注可能已过期）。邮件筛选改为非思考模式，缩短 Agent 响应时间。安卓移除常驻通知和前台服务：App 打开时约每 5 秒检查新提醒，出现顶部横幅并留在通知栏；App 关闭后使用系统非精确后台检查，无法保证即时到达。设置页可测试顶部弹窗。即时后台推送需要另行配置 Firebase Cloud Messaging。

0.8.2 版改为每约 5 秒扫描各邮箱最近邮件（包括已读），用实际到达时间判断是否为新邮件。所有新邮件先入提醒并显示本地摘要；验证码立即提取，普通邮件的 Agent 总结在后台更新，避免模型响应拖慢发现周期。安卓顶部通知显示总结。关闭 App 后即时提醒仍需 Firebase Cloud Messaging 配置。

0.8.3 版按用户要求取消顶部弹窗，改用普通通知栏频道；保留新邮件检查、验证码和普通邮件总结。Android 通知频道重要性安装后不能直接改，因此使用新频道 ID。

0.8.4 版识别正文只有 4–8 位数字、主题没有验证码字样的邮件；回填近期漏掉的验证码总结。网页版、PWA 名称、安卓桌面名称和 Agent 文案统一改为“邮箱总管”，包名及更新接口保持兼容。

0.8.5 版在安卓通知和网页推送中显示收件邮箱地址，同时保留邮件主题和总结，方便区分多个邮箱。

0.9.0 版提供“清爽工作台”和“深色指挥台”两套界面。网页在智能设置中选择主题，并保存在当前浏览器；安卓在设置的“界面主题”中选择，并保存在当前手机。提醒、验证码、聊天、用量和表单跟随主题切换。网页采用响应式侧栏或顶部导航；旧账号、App 包名和 OTA 更新路径保持兼容。

0.9.1 版在网页和安卓提醒页增加“全部已阅”。操作会把当前用户的全部未阅提醒标为已阅；安卓同时清除对应通知。此操作只改变邮箱总管的提醒状态，不会修改原邮箱邮件的已读状态。

0.3.0 版重新设计安卓界面：首页集中显示提醒、Agent 总结和原邮箱入口；设置页管理 QQ 邮箱、DeepSeek、通知与应用更新。Gmail 和 Outlook 暂未启用。

发布下一版时，先增加 android/app/build.gradle 的 versionCode 和 versionName，使用同一签名密钥构建并上传 APK，最后更新 web/update.json 中的版本和 APK SHA-256。调试签名依赖构建机的 ~/.android/debug.keystore；换构建机或正式发布时必须妥善管理签名密钥，否则无法覆盖安装旧版。

重新构建：

    cd android
    gradle assembleDebug

需要 JDK 17、Android SDK Platform 34、Android Gradle Plugin 8.2。未安装全局 gradle 时可用 Gradle Wrapper 或 Android Studio 构建。

## 运行边界

关闭正文总结时，智能体只根据邮件标题等头部信息筛选。开启后会分析有限长度的正文片段；长邮件的总结可能不完整。智能体不发送、删除或标记原邮件，也不访问附件。IMAP 账号需要在各自邮箱后台启用该服务。网站目前采用单进程调度器；如扩展到大量用户，应将调度任务迁到独立队列并改用 PostgreSQL。安卓后台检查为系统允许的近似周期，无法保证精确到分钟。
