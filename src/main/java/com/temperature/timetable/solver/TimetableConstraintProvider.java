package com.temperature.timetable.solver;

import java.time.DayOfWeek;
import java.util.Set;

import ai.timefold.solver.core.api.score.HardSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.Joiners;
import com.temperature.timetable.domain.Lesson;
import com.temperature.timetable.domain.TeacherUnavailable;

public class TimetableConstraintProvider implements ConstraintProvider {

    @Override
    public Constraint[] defineConstraints(ConstraintFactory factory) {
        return new Constraint[] {
                teacherConflict(factory),
                studentGroupConflict(factory),
                teacherUnavailable(factory),
                gradeTwoNoLateClass(factory),
                secondarySubjectNotFirstPeriod(factory),
                secondarySubjectAfterMainSubjects(factory),
                teacherNoThreeConsecutive(factory),
                minimizeChanges(factory),
                teacherConsecutiveLoad(factory),
                spreadSameSubject(factory),
                coreSubjectMorningPreference(factory)
        };
    }

    Constraint teacherConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTimeslot),
                        Joiners.equal(Lesson::getTeacher))
                .filter((a, b) -> !isAllowedCombinedPe(a, b))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Teacher conflict");
    }

    Constraint studentGroupConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTimeslot),
                        Joiners.equal(Lesson::getStudentGroup))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Student group conflict");
    }

    Constraint teacherUnavailable(ConstraintFactory factory) {
        return factory.forEach(Lesson.class)
                .join(TeacherUnavailable.class,
                        Joiners.equal(Lesson::getTeacher, TeacherUnavailable::teacher),
                        Joiners.equal(Lesson::getTimeslot, TeacherUnavailable::timeslot))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Teacher unavailable");
    }

    Constraint gradeTwoNoLateClass(ConstraintFactory factory) {
        return factory.forEach(Lesson.class)
                .filter(lesson -> lesson.getStudentGroup().equals("二1")
                        && lesson.getTimeslot().getDayOfWeek() != DayOfWeek.MONDAY
                        && lesson.getTimeslot().getPeriod() == 6)
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Grade 2 no period 6 Tue-Fri");
    }

    Constraint secondarySubjectNotFirstPeriod(ConstraintFactory factory) {
        return factory.forEach(Lesson.class)
                .filter(lesson -> lesson.getTimeslot().getPeriod() == 1
                        && isSecondarySubject(lesson.getSubject()))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Secondary subjects cannot be period 1");
    }

    Constraint secondarySubjectAfterMainSubjects(ConstraintFactory factory) {
        // School scheduling convention: Chinese and mathematics are the main subjects.
        // On the same day, a secondary subject may not appear before a later Chinese/math lesson.
        // English keeps its specialist-teacher availability window and is therefore not part of
        // this ordering rule.
        return factory.forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getStudentGroup),
                        Joiners.equal(lesson -> lesson.getTimeslot().getDayOfWeek()))
                .filter((a, b) -> isSecondaryBeforeMain(a, b) || isSecondaryBeforeMain(b, a))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Main subjects before secondary subjects");
    }

    Constraint teacherNoThreeConsecutive(ConstraintFactory factory) {
        // Three consecutive periods means the complete morning block (1-2-3)
        // or the complete afternoon block (4-5-6). The lunch break separates the blocks.
        // 二1 PE mirror entries do not consume the PE teacher's resource.
        // 黄爱珠 must teach three English classes on Tue/Thu afternoons because those are
        // her explicit availability windows; this is the only deliberate 3-period exception.
        return factory.forEach(Lesson.class)
                .filter(lesson -> !isDisplayOnlyCombinedPe(lesson))
                .join(factory.forEach(Lesson.class)
                                .filter(lesson -> !isDisplayOnlyCombinedPe(lesson)),
                        Joiners.equal(Lesson::getTeacher),
                        Joiners.equal(lesson -> lesson.getTimeslot().getDayOfWeek()),
                        Joiners.lessThan(Lesson::getId))
                .join(factory.forEach(Lesson.class)
                        .filter(lesson -> !isDisplayOnlyCombinedPe(lesson)))
                .filter((a, b, c) -> c.getId().compareTo(b.getId()) > 0
                        && a.getTeacher().equals(c.getTeacher())
                        && a.getTimeslot().getDayOfWeek() == c.getTimeslot().getDayOfWeek()
                        && formsThreePeriodBlock(a, b, c)
                        && !isAllowedThreeConsecutiveException(a, b, c))
                .penalize(HardSoftScore.ONE_HARD)
                .asConstraint("Teacher cannot teach three consecutive periods");
    }

    Constraint minimizeChanges(ConstraintFactory factory) {
        return factory.forEach(Lesson.class)
                .filter(Lesson::isChangedFromOriginal)
                .penalize(HardSoftScore.ofSoft(1000))
                .asConstraint("Minimize changes from approved timetable");
    }

    Constraint teacherConsecutiveLoad(ConstraintFactory factory) {
        return factory.forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTeacher),
                        Joiners.equal(lesson -> lesson.getTimeslot().getDayOfWeek()))
                .filter((a, b) -> !isDisplayOnlyCombinedPe(a)
                        && !isDisplayOnlyCombinedPe(b)
                        && Math.abs(a.getTimeslot().getPeriod() - b.getTimeslot().getPeriod()) == 1)
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Teacher consecutive load");
    }

    Constraint spreadSameSubject(ConstraintFactory factory) {
        return factory.forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getStudentGroup),
                        Joiners.equal(Lesson::getSubject),
                        Joiners.equal(lesson -> lesson.getTimeslot().getDayOfWeek()))
                .penalize(HardSoftScore.ofSoft(2))
                .asConstraint("Spread same subject across week");
    }

    Constraint coreSubjectMorningPreference(ConstraintFactory factory) {
        return factory.forEach(Lesson.class)
                .filter(lesson -> isMainSubject(lesson.getSubject())
                        && lesson.getTimeslot().getPeriod() > 2)
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Chinese and math prefer morning periods 1-2");
    }

    private static boolean isSecondaryBeforeMain(Lesson maybeSecondary, Lesson maybeMain) {
        return isSecondarySubject(maybeSecondary.getSubject())
                && isMainSubject(maybeMain.getSubject())
                && maybeSecondary.getTimeslot().getPeriod() < maybeMain.getTimeslot().getPeriod();
    }

    private static boolean isMainSubject(String subject) {
        return subject.equals("语文") || subject.equals("数学");
    }

    private static boolean isSecondarySubject(String subject) {
        return !isMainSubject(subject);
    }

    private static boolean formsThreePeriodBlock(Lesson a, Lesson b, Lesson c) {
        Set<Integer> periods = Set.of(
                a.getTimeslot().getPeriod(),
                b.getTimeslot().getPeriod(),
                c.getTimeslot().getPeriod());
        return periods.equals(Set.of(1, 2, 3)) || periods.equals(Set.of(4, 5, 6));
    }

    private static boolean isAllowedThreeConsecutiveException(Lesson a, Lesson b, Lesson c) {
        if (!a.getTeacher().equals("黄爱珠")
                || !b.getTeacher().equals("黄爱珠")
                || !c.getTeacher().equals("黄爱珠")) {
            return false;
        }
        DayOfWeek day = a.getTimeslot().getDayOfWeek();
        return (day == DayOfWeek.TUESDAY || day == DayOfWeek.THURSDAY)
                && a.getSubject().equals("英语")
                && b.getSubject().equals("英语")
                && c.getSubject().equals("英语")
                && formsThreePeriodBlock(a, b, c);
    }

    private static boolean isDisplayOnlyCombinedPe(Lesson lesson) {
        if (!lesson.getTeacher().equals("柯冬梅")
                || !lesson.getStudentGroup().equals("二1")
                || !lesson.getSubject().contains("体育")) {
            return false;
        }
        DayOfWeek day = lesson.getTimeslot().getDayOfWeek();
        int period = lesson.getTimeslot().getPeriod();
        return (day == DayOfWeek.MONDAY && period == 2)
                || (day == DayOfWeek.WEDNESDAY && period == 4)
                || (day == DayOfWeek.FRIDAY && period == 3);
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
}
