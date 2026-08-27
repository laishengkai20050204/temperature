package com.temperature.timetable;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.temperature.timetable.domain.Lesson;
import com.temperature.timetable.domain.TeacherUnavailable;
import com.temperature.timetable.domain.Timetable;
import com.temperature.timetable.io.SchoolBaselineTsvIO;
import com.temperature.timetable.io.TimetableExcelIO;
import com.temperature.timetable.solver.TimetableConstraintProvider;

public final class TemperatureTimetableApplication {

    private TemperatureTimetableApplication() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Usage: java -jar temperature-timetable.jar <input.xlsx|input.tsv> [output.xlsx]");
            System.out.println("Supports the native school workbook, normalized school TSV, and standard Timeslots/Lessons format.");
            return;
        }

        Path input = Path.of(args[0]);
        Path output = args.length == 2 ? Path.of(args[1]) : Path.of("solved-timetable.xlsx");
        boolean tsvInput = input.getFileName().toString().toLowerCase().endsWith(".tsv");
        boolean schoolWorkbook = !tsvInput && TimetableExcelIO.isSchoolWorkbook(input);

        Timetable problem = tsvInput ? SchoolBaselineTsvIO.read(input) : TimetableExcelIO.read(input);
        Timetable solution = solve(problem, Duration.ofSeconds(90));

        if (schoolWorkbook) {
            TimetableExcelIO.writeSchoolWorkbook(input, solution, output);
        } else {
            TimetableExcelIO.write(solution, output);
        }

        long changed = solution.getLessons().stream().filter(Lesson::isChangedFromOriginal).count();
        long movedPe = solution.getLessons().stream()
                .filter(lesson -> lesson.getSubject().contains("体育") && lesson.isChangedFromOriginal())
                .count();
        long secondaryPeriod2 = solution.getLessons().stream()
                .filter(lesson -> lesson.getTimeslot() != null
                        && lesson.getTimeslot().getPeriod() == 2
                        && isSecondarySubject(lesson.getSubject())
                        && !lesson.getSubject().contains("体育"))
                .count();
        long laiMingyaSecondaryPeriod2 = solution.getLessons().stream()
                .filter(lesson -> lesson.getTimeslot() != null
                        && lesson.getTeacher().equals("赖明雅")
                        && lesson.getTimeslot().getPeriod() == 2
                        && isSecondarySubject(lesson.getSubject())
                        && !lesson.getSubject().contains("体育"))
                .count();
        System.out.println("Solved score: " + solution.getScore());
        System.out.println("Changed lessons: " + changed);
        System.out.println("Moved PE lessons: " + movedPe);
        System.out.println("Secondary period-2 preference count: " + secondaryPeriod2);
        System.out.println("Lai Mingya secondary period-2 count: " + laiMingyaSecondaryPeriod2);
        printHardViolationDiagnostics(solution);
        System.out.println("Output: " + output.toAbsolutePath());
    }

    public static Timetable solve(Timetable problem, Duration limit) {
        SolverFactory<Timetable> solverFactory = SolverFactory.create(new SolverConfig()
                .withSolutionClass(Timetable.class)
                .withEntityClasses(Lesson.class)
                .withConstraintProviderClass(TimetableConstraintProvider.class)
                .withTerminationSpentLimit(limit));
        Solver<Timetable> solver = solverFactory.buildSolver();
        return solver.solve(problem);
    }

    private static void printHardViolationDiagnostics(Timetable timetable) {
        List<String> violations = new ArrayList<>();
        List<Lesson> lessons = timetable.getLessons();

        for (int i = 0; i < lessons.size(); i++) {
            Lesson a = lessons.get(i);
            if (a.getTimeslot() == null) continue;
            for (int j = i + 1; j < lessons.size(); j++) {
                Lesson b = lessons.get(j);
                if (b.getTimeslot() == null) continue;
                if (a.getTimeslot().equals(b.getTimeslot())) {
                    if (a.getTeacher().equals(b.getTeacher()) && !isAllowedCombinedPe(a, b)) {
                        violations.add("Teacher conflict: " + slot(a) + " " + a.getTeacher()
                                + " -> " + label(a) + " | " + label(b));
                    }
                    if (a.getStudentGroup().equals(b.getStudentGroup())) {
                        violations.add("Class conflict: " + slot(a) + " " + a.getStudentGroup()
                                + " -> " + a.getSubject() + " | " + b.getSubject());
                    }
                }
                if (a.getStudentGroup().equals(b.getStudentGroup())
                        && a.getTimeslot().getDayOfWeek() == b.getTimeslot().getDayOfWeek()) {
                    if (isSecondaryBeforeMain(a, b)) {
                        violations.add("Secondary before main: " + label(a) + " " + slot(a)
                                + " before " + b.getSubject() + " " + slot(b));
                    }
                    if (isSecondaryBeforeMain(b, a)) {
                        violations.add("Secondary before main: " + label(b) + " " + slot(b)
                                + " before " + a.getSubject() + " " + slot(a));
                    }
                }
            }

            if (a.getStudentGroup().equals("二1")
                    && a.getTimeslot().getDayOfWeek() != DayOfWeek.MONDAY
                    && a.getTimeslot().getPeriod() == 6) {
                violations.add("Grade 2 late class: " + label(a) + " " + slot(a));
            }
            if (a.getTimeslot().getPeriod() == 1
                    && isSecondarySubject(a.getSubject())
                    && !a.getSubject().contains("体育")) {
                violations.add("Secondary period 1: " + label(a) + " " + slot(a));
            }
            for (TeacherUnavailable unavailable : timetable.getTeacherUnavailableList()) {
                if (a.getTeacher().equals(unavailable.teacher())
                        && a.getTimeslot().equals(unavailable.timeslot())) {
                    violations.add("Teacher unavailable: " + label(a) + " " + slot(a));
                }
            }
        }

        List<String> teacherOrderDays = lessons.stream()
                .filter(l -> l.getTimeslot() != null && !isDisplayOnlyCombinedPe(l))
                .map(l -> l.getTeacher() + "|" + l.getTimeslot().getDayOfWeek())
                .distinct()
                .toList();
        for (String key : teacherOrderDays) {
            String[] parts = key.split("\\|", -1);
            String teacher = parts[0];
            DayOfWeek day = DayOfWeek.valueOf(parts[1]);
            List<Lesson> dayLessons = lessons.stream()
                    .filter(l -> l.getTimeslot() != null
                            && !isDisplayOnlyCombinedPe(l)
                            && l.getTeacher().equals(teacher)
                            && l.getTimeslot().getDayOfWeek() == day)
                    .toList();
            for (Lesson secondary : dayLessons) {
                if (!isSecondarySubject(secondary.getSubject())) continue;
                for (Lesson main : dayLessons) {
                    if (isSecondaryBeforeMain(secondary, main)) {
                        violations.add("Teacher secondary before main: " + teacher + " " + day
                                + " -> " + label(secondary) + " " + slot(secondary)
                                + " before " + label(main) + " " + slot(main));
                    }
                }
            }
        }

        List<String> teacherDays = lessons.stream()
                .filter(l -> l.getTimeslot() != null && !isDisplayOnlyCombinedPe(l))
                .map(l -> l.getTeacher() + "|" + l.getTimeslot().getDayOfWeek())
                .distinct()
                .toList();
        for (String key : teacherDays) {
            String[] parts = key.split("\\|", -1);
            String teacher = parts[0];
            DayOfWeek day = DayOfWeek.valueOf(parts[1]);
            List<Lesson> dayLessons = lessons.stream()
                    .filter(l -> l.getTimeslot() != null
                            && !isDisplayOnlyCombinedPe(l)
                            && l.getTeacher().equals(teacher)
                            && l.getTimeslot().getDayOfWeek() == day)
                    .sorted(Comparator.comparingInt(l -> l.getTimeslot().getPeriod()))
                    .toList();
            for (int start : new int[] {1, 4}) {
                Lesson p1 = atPeriod(dayLessons, start);
                Lesson p2 = atPeriod(dayLessons, start + 1);
                Lesson p3 = atPeriod(dayLessons, start + 2);
                if (p1 != null && p2 != null && p3 != null && !isAllowedThreeConsecutiveException(p1, p2, p3)) {
                    violations.add("Teacher three consecutive: " + teacher + " " + day
                            + " periods " + start + "-" + (start + 2)
                            + " -> " + label(p1) + " | " + label(p2) + " | " + label(p3));
                }
            }
        }

        System.out.println("Hard violation diagnostics: " + violations.size());
        for (String violation : violations) {
            System.out.println("VIOLATION: " + violation);
        }
    }

    private static Lesson atPeriod(List<Lesson> lessons, int period) {
        return lessons.stream().filter(l -> l.getTimeslot().getPeriod() == period).findFirst().orElse(null);
    }

    private static boolean isSecondaryBeforeMain(Lesson secondary, Lesson main) {
        return isSecondarySubject(secondary.getSubject())
                && isMainSubject(main.getSubject())
                && secondary.getTimeslot().getPeriod() < main.getTimeslot().getPeriod();
    }

    private static boolean isMainSubject(String subject) {
        return subject.equals("语文") || subject.equals("数学");
    }

    private static boolean isSecondarySubject(String subject) {
        return !isMainSubject(subject);
    }

    private static boolean isAllowedThreeConsecutiveException(Lesson a, Lesson b, Lesson c) {
        if (!a.getTeacher().equals("黄爱珠") || !b.getTeacher().equals("黄爱珠") || !c.getTeacher().equals("黄爱珠")) {
            return false;
        }
        DayOfWeek day = a.getTimeslot().getDayOfWeek();
        return (day == DayOfWeek.TUESDAY || day == DayOfWeek.THURSDAY)
                && a.getSubject().equals("英语") && b.getSubject().equals("英语") && c.getSubject().equals("英语");
    }

    private static boolean isDisplayOnlyCombinedPe(Lesson lesson) {
        if (!lesson.getTeacher().equals("柯冬梅") || !lesson.getStudentGroup().equals("二1")
                || !lesson.getSubject().contains("体育")) return false;
        String original = lesson.getOriginalTimeslotId();
        return "MON-2".equals(original) || "WED-4".equals(original) || "FRI-3".equals(original);
    }

    private static boolean isExpectedCombinedPeDisplaySlot(Lesson lesson) {
        String original = lesson.getOriginalTimeslotId();
        DayOfWeek day = lesson.getTimeslot().getDayOfWeek();
        int period = lesson.getTimeslot().getPeriod();
        if ("MON-2".equals(original)) return day == DayOfWeek.MONDAY && period == 3;
        if ("WED-4".equals(original)) return day == DayOfWeek.WEDNESDAY && period == 5;
        if ("FRI-3".equals(original)) return day == DayOfWeek.FRIDAY && period == 3;
        return false;
    }

    private static boolean isAllowedCombinedPe(Lesson a, Lesson b) {
        if (!a.getTeacher().equals("柯冬梅") || !b.getTeacher().equals("柯冬梅")) return false;
        if (!a.getSubject().contains("体育") || !b.getSubject().contains("体育")) return false;
        boolean groupsMatch = (a.getStudentGroup().equals("二1") && b.getStudentGroup().equals("三1"))
                || (a.getStudentGroup().equals("三1") && b.getStudentGroup().equals("二1"));
        if (!groupsMatch) return false;
        DayOfWeek day = a.getTimeslot().getDayOfWeek();
        int period = a.getTimeslot().getPeriod();
        return (day == DayOfWeek.MONDAY && period == 3)
                || (day == DayOfWeek.WEDNESDAY && period == 5)
                || (day == DayOfWeek.FRIDAY && period == 3);
    }

    private static String slot(Lesson lesson) {
        return lesson.getTimeslot().getDayOfWeek() + "-" + lesson.getTimeslot().getPeriod();
    }

    private static String label(Lesson lesson) {
        return lesson.getStudentGroup() + " " + lesson.getSubject() + "(" + lesson.getTeacher() + ")";
    }
}
