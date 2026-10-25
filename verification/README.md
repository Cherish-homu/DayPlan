# 外部独立回归探针

这 13 项由项目独立复验产生，使用合成材料与隔离数据库，不读取真实 Key 或个人日程，不进入 release 源码。

- `auditv11/FollowupProbeTest.kt`：3 项提醒/专注边界。
- `auditv13/IndependentV13ProbeTest.kt`：8 项批次去重、并发、取消/恢复、附件澄清与完整性。
- `auditv14/IndependentV14ProbeTest.kt`：2 项恢复提交断点与自动保存最新日历。

在项目根目录执行（Windows 用 `gradlew.bat`）：

```sh
./gradlew -I verification/audit.init.gradle :app:testReleaseUnitTest \
  --tests com.jsy.dayplan.auditv11.FollowupProbeTest \
  --tests com.jsy.dayplan.auditv13.IndependentV13ProbeTest \
  --tests com.jsy.dayplan.auditv14.IndependentV14ProbeTest --no-daemon
```

不指定 init 脚本时，正式套件为 163 项；指定上述 init 和筛选时执行外部 13 项。两套报告的默认输出目录相同，应分别归档结果。v1.5 在原 Windows / JDK17 环境，两套均通过；断点测试是故障注入，不能表述为已做真实拔电。
