# temperature — 小学自动排课

基于 [Timefold Solver](https://github.com/TimefoldAI/timefold-solver) 的约束求解排课项目。

## 当前目标

第一阶段先把“成熟求解器 + 小学排课领域模型 + Excel 输入输出”打通，后续再逐条接入真实学校规则。

当前已规划的约束：

- 同一教师同一时间不能上两节课（Hard）
- 同一班级同一时间不能上两节课（Hard）
- 教师不可用时段不能排课（Hard）
- 指定课程固定时段必须保留（Hard）
- 尽量避免教师连续 3 节以上（Soft）
- 尽量保持原课表不变，实现“最小修改”（Soft）
- 同一班级同一学科尽量分散（Soft）
- 支持体育合班等特殊联动规则（后续按学校实际规则实现）

## 技术栈

- Java 21
- Timefold Solver 2.5.x
- Apache POI 5.5.x
- Maven

## 运行

```bash
mvn test
mvn package
java -jar target/temperature-timetable-0.1.0-SNAPSHOT.jar
```

## Excel

当前基础版使用标准化输入表，后续会增加对现有学校课表 Excel 的直接读取和原格式写回。

建议标准化输入包含：

- `Lessons`：课程、教师、班级、原时段、是否锁定
- `Timeslots`：星期、节次
- `TeacherUnavailable`：教师不可用时段

## 上游项目与许可证

排课建模方式参考并改造自 Timefold Quickstarts 的 `school-timetabling` 示例：

- https://github.com/TimefoldAI/timefold-quickstarts/tree/stable/use-cases/school-timetabling

Timefold Quickstarts 使用 Apache License 2.0。本仓库保留相应许可证与来源说明。
