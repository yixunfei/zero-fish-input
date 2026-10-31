# 本机开发环境（2026-10-30）

本文件为本机说明，不替代项目技术契约或发布门禁。跨机器优先通过 PATH、环境变量和项目脚本定位工具，历史日志里的盘符不是当前默认值。

- 当前工作区：`K:\zeroInput`。原项目盘 L: 已改为 K:，原 Docker/备份盘 M: 已改为 L:。
- Docker 服务资源根：L:/docker_space；当前引擎数据：L:/docker_space/desktop-20261030。本机 context 为 desktop-linux，不批量启动旧业务服务。
- GRADLE_USER_HOME=E:/gradle-cache；Maven 本地仓库 D:/mvn_rep。工具与镜像配置是本机设置，不写死到业务源代码。
- Flutter 3.44.1 / Dart 3.12.1；JAVA_HOME 使用 JDK21，要求 Java17 的 Gradle 模块使用独立 MicrosoftJDK17 工具链。
- Git Perl 标准 site_perl 已补齐依赖；Rust 按项目 rust-toolchain.toml 定位，libclang 通过环境变量定位。无需复制旧的 C 盘缓存或临时 wrapper。
- 2026-10-30 本机已实际打包通过；该结论不是全部业务测试、正式签名发布或生产部署验收。后续改动按项目规则补做相应验证。

## 当前入口

- `README.md`

## 清理与恢复

保留源素材、未提交改动、计划/评审证据、签名材料及业务数据。无引用的临时日志由本轮清理清单记录并可回退；不要清空整个 .r 或 .tmp-evidence，这些目录可能仍含有效原生依赖和测试证据。

跨项目恢复与验收记录：C:/Users/yixun/Documents/Codex/2026-10-30/da/outputs/恢复状态.md。
