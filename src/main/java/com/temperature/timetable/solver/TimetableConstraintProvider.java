package com.temperature.timetable.solver;

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
                minimizeChanges(factory),
                teacherConsecutiveLoad(factory),
                spreadSameSubject(factory)
        };
    }

    Constraint teacherConflict(ConstraintFactory factory) {
        return factory.forEachUniquePair(Lesson.class,
                        Joiners.equal(Lesson::getTimeslot),
                        Joiners.equal(Lesson::getTeacher))
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

    Constraint minimizeChanges(ConstraintFactory factory) {
        return factory.forEach(Lesson.class)
                .filter(Lesson::isChangedFromOriginal)
                .penalize(HardSoftScore.ofSoft(20))
                .asConstraint("Minimize changes from original timetable");
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
}
