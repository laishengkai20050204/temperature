#!/usr/bin/env python3
import argparse
import csv
import math
from collections import defaultdict
from itertools import combinations
from pathlib import Path

from ortools.sat.python import cp_model

DAYS = ["MON", "TUE", "WED", "THU", "FRI"]
PERIODS = range(1, 7)
MAIN = {"语文", "数学"}


def is_main(subject):
    return subject in MAIN


def is_secondary(subject):
    return subject not in MAIN


def is_pe(subject):
    return "体育" in subject


def slot_key(day, period):
    return (day, int(period))


def display_only_combined_pe(row):
    # 二1中柯冬梅的三节体育是“正式课表显示位”，实际与三1合班执行，
    # 因此不占用柯冬梅真实授课资源。
    return (
        row["class"] == "二1"
        and row["teacher"] == "柯冬梅"
        and is_pe(row["subject"])
    )


def friday_grade2_display_pe(row):
    return (
        display_only_combined_pe(row)
        and row["day"] == "FRI"
    )


def read_rows(path):
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        for i, row in enumerate(csv.DictReader(f, delimiter="\t")):
            row = dict(row)
            row["period"] = int(row["period"])
            row["_idx"] = i
            rows.append(row)
    return rows


def build_unavailable(rows):
    chinese = {r["teacher"] for r in rows if r["subject"] == "语文"}
    math_teachers = {r["teacher"] for r in rows if r["subject"] == "数学"}
    english = {r["teacher"] for r in rows if r["subject"] == "英语"}
    unavailable = defaultdict(set)

    for t in chinese:
        unavailable[t].update({("TUE", 5), ("TUE", 6)})
    for t in math_teachers:
        unavailable[t].update({("THU", 5), ("THU", 6)})

    unavailable["姚金钗"].update({("MON", 1), ("MON", 2), ("MON", 3)})

    for t in english:
        for d in DAYS:
            for p in PERIODS:
                if not (d in {"TUE", "THU"} and p >= 4):
                    unavailable[t].add((d, p))

    for d in DAYS:
        for p in PERIODS:
            if not (d in {"MON", "FRI"} and p >= 4):
                unavailable["林燕慧"].add((d, p))

    for d in {"MON", "TUE", "THU"}:
        unavailable["柯冬梅"].update({(d, 5), (d, 6)})

    return unavailable


def make_and(model, a, b, name):
    v = model.NewBoolVar(name)
    model.Add(v <= a)
    model.Add(v <= b)
    model.Add(v >= a + b - 1)
    return v


def solve(rows, output_path, time_limit):
    slots = [(d, p) for d in DAYS for p in PERIODS]
    model = cp_model.CpModel()

    x = {}
    for r in rows:
        i = r["_idx"]
        for s in slots:
            x[i, s] = model.NewBoolVar(f"x_{i}_{s[0]}_{s[1]}")
        model.Add(sum(x[i, s] for s in slots) == 1)

    # 体育实际时段全部锁定。唯一允许变化的是二1周五“正式显示位”，
    # 因用户明确要求正式课表中二1、三1不要排在同一节。
    for r in rows:
        if is_pe(r["subject"]) and not friday_grade2_display_pe(r):
            orig = slot_key(r["day"], r["period"])
            model.Add(x[r["_idx"], orig] == 1)

    # 二1周五正式体育必须仍在周五，并与三1周五体育错开。
    friday_display = [r for r in rows if friday_grade2_display_pe(r)]
    if len(friday_display) != 1:
        raise SystemExit(f"Expected exactly one grade-2 Friday PE display row, got {len(friday_display)}")
    grade2_friday_pe = friday_display[0]
    model.Add(sum(x[grade2_friday_pe["_idx"], ("FRI", p)] for p in PERIODS) == 1)

    grade3_friday_pe = [
        r for r in rows
        if r["class"] == "三1" and r["teacher"] == "柯冬梅"
        and is_pe(r["subject"]) and r["day"] == "FRI"
    ]
    if len(grade3_friday_pe) != 1:
        raise SystemExit(f"Expected exactly one grade-3 Friday PE row, got {len(grade3_friday_pe)}")
    p3_fixed = grade3_friday_pe[0]["period"]
    model.Add(x[grade2_friday_pe["_idx"], ("FRI", p3_fixed)] == 0)

    # 每个班同一时段最多一节。
    by_class = defaultdict(list)
    for r in rows:
        by_class[r["class"]].append(r)
    for group_rows in by_class.values():
        for s in slots:
            model.Add(sum(x[r["_idx"], s] for r in group_rows) <= 1)

    # 教师同一时段最多一节；二1合班体育仅为正式显示镜像，不占教师真实资源。
    by_teacher = defaultdict(list)
    for r in rows:
        if not display_only_combined_pe(r):
            by_teacher[r["teacher"]].append(r)
    for teacher_rows in by_teacher.values():
        for s in slots:
            model.Add(sum(x[r["_idx"], s] for r in teacher_rows) <= 1)

    unavailable = build_unavailable(rows)
    for r in rows:
        if display_only_combined_pe(r):
            continue
        for s in unavailable.get(r["teacher"], set()):
            model.Add(x[r["_idx"], s] == 0)

    # 二1周二至周五第6节不上课。
    for r in rows:
        if r["class"] == "二1":
            for d in {"TUE", "WED", "THU", "FRI"}:
                model.Add(x[r["_idx"], (d, 6)] == 0)

    # 非体育次科不排第1节。
    for r in rows:
        if is_secondary(r["subject"]) and not is_pe(r["subject"]):
            for d in DAYS:
                model.Add(x[r["_idx"], (d, 1)] == 0)

    # 赖明雅的次科不排第2节；其它教师第2节次科仍按软优化处理。
    for r in rows:
        if (
            r["teacher"] == "赖明雅"
            and is_secondary(r["subject"])
            and not is_pe(r["subject"])
        ):
            for d in DAYS:
                model.Add(x[r["_idx"], (d, 2)] == 0)

    # 用户指定：五1吴淑治周二书法移到周三。
    wu_calligraphy = [
        r for r in rows
        if r["class"] == "五1" and r["subject"] == "书法" and r["teacher"] == "吴淑治"
    ]
    if len(wu_calligraphy) != 1:
        raise SystemExit(f"Expected one 五1书法(吴淑治), got {len(wu_calligraphy)}")
    model.Add(sum(x[wu_calligraphy[0]["_idx"], ("WED", p)] for p in PERIODS) == 1)

    # 用户指定：吴淑治周三的五1语文至少一节放第1节。
    wu_wed_chinese = [
        r for r in rows
        if r["class"] == "五1" and r["subject"] == "语文" and r["teacher"] == "吴淑治"
    ]
    if not wu_wed_chinese:
        raise SystemExit("No 五1语文(吴淑治) found")
    model.Add(sum(x[r["_idx"], ("WED", 1)] for r in wu_wed_chinese) == 1)

    # 班级层面：同一天语文/数学必须在次科之前。
    for group_rows in by_class.values():
        mains = [r for r in group_rows if is_main(r["subject"])]
        secondary = [r for r in group_rows if is_secondary(r["subject"]) and not is_pe(r["subject"])]
        for sec in secondary:
            for main in mains:
                for d in DAYS:
                    for ps in PERIODS:
                        for pm in PERIODS:
                            if ps < pm:
                                model.Add(
                                    x[sec["_idx"], (d, ps)] + x[main["_idx"], (d, pm)] <= 1
                                )

    # 教师个人层面：当天自己承担的语文/数学也必须在其次科之前。
    for teacher_rows in by_teacher.values():
        mains = [r for r in teacher_rows if is_main(r["subject"])]
        secondary = [r for r in teacher_rows if is_secondary(r["subject"]) and not is_pe(r["subject"])]
        for sec in secondary:
            for main in mains:
                for d in DAYS:
                    for ps in PERIODS:
                        for pm in PERIODS:
                            if ps < pm:
                                model.Add(
                                    x[sec["_idx"], (d, ps)] + x[main["_idx"], (d, pm)] <= 1
                                )

    # 每班每天语文+数学最多3节。
    # 第一优化目标是把“超过2节”的总量压到理论最低：
    # 每周10节 -> 2+2+2+2+2；每周11节 -> 3+2+2+2+2。
    overload_terms = []
    core_count_vars = {}
    for cls, group_rows in by_class.items():
        main_rows = [r for r in group_rows if is_main(r["subject"])]
        for d in DAYS:
            count = model.NewIntVar(0, 3, f"core_{cls}_{d}")
            model.Add(count == sum(x[r["_idx"], (d, p)] for r in main_rows for p in PERIODS))
            model.Add(count <= 3)
            core_count_vars[(cls, d)] = count
            overload = model.NewIntVar(0, 1, f"core_overload_{cls}_{d}")
            model.AddMaxEquality(overload, [count - 2, 0])
            overload_terms.append(overload)

    # 理论最均衡主科负荷作为硬目标：
    # 每周10节语数的班级总超载应为0；每周11节的班级总超载应为1。
    theoretical_overload = 0
    for cls, group_rows in by_class.items():
        weekly_main = sum(1 for r in group_rows if is_main(r["subject"]))
        theoretical_overload += max(0, weekly_main - 10)
    model.Add(sum(overload_terms) == theoretical_overload)

    # 普通教师上午1-3、下午4-6均不能连续上满三节。
    for teacher, teacher_rows in by_teacher.items():
        for d in DAYS:
            for block in ((1, 2, 3), (4, 5, 6)):
                if teacher == "黄爱珠" and d in {"TUE", "THU"} and block == (4, 5, 6):
                    continue
                model.Add(
                    sum(
                        x[r["_idx"], (d, p)]
                        for r in teacher_rows
                        for p in block
                    )
                    <= 2
                )

    # 连堂课软偏好：同班同科若一天出现两节，优先安排为上午第2、3节。
    duplicate_bad_terms = []
    by_class_subject = defaultdict(list)
    for r in rows:
        by_class_subject[(r["class"], r["subject"])].append(r)

    for (cls, subject), same_rows in by_class_subject.items():
        if len(same_rows) < 2:
            continue
        for a, b in combinations(same_rows, 2):
            ia, ib = a["_idx"], b["_idx"]
            for d in DAYS:
                a_day = sum(x[ia, (d, p)] for p in PERIODS)
                b_day = sum(x[ib, (d, p)] for p in PERIODS)
                both = model.NewBoolVar(f"same_day_{ia}_{ib}_{d}")
                model.Add(both <= a_day)
                model.Add(both <= b_day)
                model.Add(both >= a_day + b_day - 1)

                g1 = make_and(model, x[ia, (d, 2)], x[ib, (d, 3)], f"g1_{ia}_{ib}_{d}")
                g2 = make_and(model, x[ia, (d, 3)], x[ib, (d, 2)], f"g2_{ia}_{ib}_{d}")
                good23 = model.NewBoolVar(f"good23_{ia}_{ib}_{d}")
                model.Add(good23 == g1 + g2)

                # 若同一天重复，但不是2-3连堂，就记一个软惩罚。
                duplicate_bad_terms.append(both)
                duplicate_bad_terms.append(-good23)

    # 其它次科第2节仍然尽量减少（体育除外）。
    second_secondary_terms = []
    for r in rows:
        if is_secondary(r["subject"]) and not is_pe(r["subject"]):
            for d in DAYS:
                second_secondary_terms.append(x[r["_idx"], (d, 2)])

    # 先把语数日负荷压到理论最优，再尽量少改；之后优化连堂2-3和次科第2节。
    moved_terms = []
    for r in rows:
        orig = slot_key(r["day"], r["period"])
        moved_terms.append(1 - x[r["_idx"], orig])

    model.Minimize(
        10000 * sum(moved_terms)
        + 100 * sum(duplicate_bad_terms)
        + 10 * sum(second_secondary_terms)
    )

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = float(time_limit)
    solver.parameters.num_search_workers = 8
    solver.parameters.log_search_progress = False

    status = solver.Solve(model)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        raise SystemExit(f"No feasible timetable found. status={solver.StatusName(status)}")

    solved = []
    moved = 0
    period2_secondary = 0
    move_report = []
    for r in rows:
        assigned = next(s for s in slots if solver.Value(x[r["_idx"], s]))
        out = dict(r)
        out["day"], out["period"] = assigned
        if assigned != slot_key(r["day"], r["period"]):
            moved += 1
            move_report.append(
                (r["class"], r["subject"], r["teacher"], r["day"], r["period"], assigned[0], assigned[1])
            )
        if assigned[1] == 2 and is_secondary(r["subject"]) and not is_pe(r["subject"]):
            period2_secondary += 1
        solved.append(out)

    day_order = {d: i for i, d in enumerate(DAYS)}
    solved.sort(key=lambda r: (r["class"], day_order[r["day"]], r["period"]))

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["class", "subject", "teacher", "day", "period"])
        for r in solved:
            w.writerow([r["class"], r["subject"], r["teacher"], r["day"], r["period"]])

    print(f"Exact status: {solver.StatusName(status)}")
    print(f"Exact moved lessons: {moved}")
    print(f"Exact non-PE secondary period-2 count: {period2_secondary}")
    print("Core balance:")
    for cls in sorted(by_class):
        counts = [solver.Value(core_count_vars[(cls, d)]) for d in DAYS]
        print(f"  {cls}: {counts}")
    for item in move_report:
        print("MOVE:", *item)
    grade2_final = next(
        (r for r in solved if r["class"] == "二1" and r["teacher"] == "柯冬梅"
         and is_pe(r["subject"]) and r["day"] == "FRI"),
        None,
    )
    grade3_final = next(
        (r for r in solved if r["class"] == "三1" and r["teacher"] == "柯冬梅"
         and is_pe(r["subject"]) and r["day"] == "FRI"),
        None,
    )
    print(f"Friday formal PE periods: 二1={grade2_final['period']} 三1={grade3_final['period']}")
    print(f"Exact objective: {solver.ObjectiveValue()}")
    print(f"Output: {output_path}")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("input")
    p.add_argument("output")
    p.add_argument("--time-limit", type=int, default=120)
    args = p.parse_args()
    solve(read_rows(args.input), args.output, args.time_limit)


if __name__ == "__main__":
    main()
