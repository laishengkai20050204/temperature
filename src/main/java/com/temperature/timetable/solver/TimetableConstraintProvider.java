package com.temperature.timetable.solver;

import java.time.DayOfWeek;

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

    Constraint minimizeChanges(ConstraintFactory factory) {
        // The uploaded workbook is the approved baseline. Moving a lesson is therefore
        // much more expensive than any ordinary preference improvement. The solver only
        // changes the baseline when required to remove a hard violation.
        return factory.forEach(Lesson.class)
                .filter(Lesson::isChangedFromOriginal)
                .penalize(HardSoftScore.ofSoft(1000))
                .asConstraint("Minimize changes from approved timetable");
    }

    Constraint teacherConsecutiveLoad(ConstraintFactory factory) {
        return factory.forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTeacher),
                        Joiners.equal(lesson -> lesson.getTimeslot().getDayOfWeek()))
                .filter((a, b) -> Math.abs(a.getTimeslot().getPeriod() - b.getTimeslot().getPeriod()) == 1)
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
                .filter(lesson -> (lesson.getSubject().equals("语文") || lesson.getSubject().equals("数学"))
                        && lesson.getTimeslot().getPeriod() > 2)
                .penalize(HardSoftScore.ONE_SOFT)
                .asConstraint("Chinese and math prefer morning periods 1-2");
    }

    private static boolean isSecondarySubject(String subject) {
        return !subject.equals("语文")
                && !subject.equals("数学")
                && !subject.equals("英语");
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
