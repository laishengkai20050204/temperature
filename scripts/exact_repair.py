#!/usr/bin/env python3
import argparse
import csv
from collections import defaultdict
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
    return (
        row["class"] == "二1"
        and row["teacher"] == "柯冬梅"
        and is_pe(row["subject"])
        and slot_key(row["day"], row["period"])
        in {("MON", 2), ("WED", 4), ("FRI", 3)}
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
    math = {r["teacher"] for r in rows if r["subject"] == "数学"}
    english = {r["teacher"] for r in rows if r["subject"] == "英语"}
    unavailable = defaultdict(set)

    for t in chinese:
        unavailable[t].update({("TUE", 5), ("TUE", 6)})
    for t in math:
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


def solve(rows, output_path, time_limit):
    slots = [(d, p) for d in DAYS for p in PERIODS]
    model = cp_model.CpModel()

    x = {}
    for r in rows:
        i = r["_idx"]
        for s in slots:
            x[i, s] = model.NewBoolVar(f"x_{i}_{s[0]}_{s[1]}")
        model.Add(sum(x[i, s] for s in slots) == 1)

    # 体育课完全锁定，不允许任何移动。
    for r in rows:
        if is_pe(r["subject"]):
            orig = slot_key(r["day"], r["period"])
            model.Add(x[r["_idx"], orig] == 1)

    # 每个班同一时段最多一节。
    by_class = defaultdict(list)
    for r in rows:
        by_class[r["class"]].append(r)
    for group_rows in by_class.values():
        for s in slots:
            model.Add(sum(x[r["_idx"], s] for r in group_rows) <= 1)

    # 教师同一时段最多一节；二1合班体育仅为显示镜像，不占教师资源。
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

    # 班级层面：同一天语文/数学必须在次科之前。
    for group_rows in by_class.values():
        mains = [r for r in group_rows if is_main(r["subject"])]
        secondary = [r for r in group_rows if is_secondary(r["subject"])]
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
        secondary = [r for r in teacher_rows if is_secondary(r["subject"])]
        for sec in secondary:
            for main in mains:
                for d in DAYS:
                    for ps in PERIODS:
                        for pm in PERIODS:
                            if ps < pm:
                                model.Add(
                                    x[sec["_idx"], (d, ps)] + x[main["_idx"], (d, pm)] <= 1
                                )

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

    # 目标：先严格最小化改动节数，再在同改动数下尽量减少第2节次科。
    moved_terms = []
    second_secondary_terms = []
    for r in rows:
        orig = slot_key(r["day"], r["period"])
        moved_terms.append(1 - x[r["_idx"], orig])
        if is_secondary(r["subject"]) and not is_pe(r["subject"]):
            for d in DAYS:
                second_secondary_terms.append(x[r["_idx"], (d, 2)])

    model.Minimize(1000 * sum(moved_terms) + sum(second_secondary_terms))

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
    for r in rows:
        assigned = next(s for s in slots if solver.Value(x[r["_idx"], s]))
        out = dict(r)
        out["day"], out["period"] = assigned
        if assigned != slot_key(r["day"], r["period"]):
            moved += 1
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
