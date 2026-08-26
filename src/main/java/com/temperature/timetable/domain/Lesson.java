package com.temperature.timetable.domain;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

@PlanningEntity
public class Lesson {

    @PlanningId
    private String id;
    private String subject;
    private String teacher;
    private String studentGroup;
    private String originalTimeslotId;

    @PlanningPin
    private boolean locked;

    @PlanningVariable(valueRangeProviderRefs = "timeslotRange")
    private Timeslot timeslot;

    public Lesson() {
    }

    public Lesson(String id, String subject, String teacher, String studentGroup,
                  String originalTimeslotId, boolean locked, Timeslot timeslot) {
        this.id = id;
        this.subject = subject;
        this.teacher = teacher;
        this.studentGroup = studentGroup;
        this.originalTimeslotId = originalTimeslotId;
        this.locked = locked;
        this.timeslot = timeslot;
    }

    public String getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getTeacher() {
        return teacher;
    }

    public String getStudentGroup() {
        return studentGroup;
    }

    public String getOriginalTimeslotId() {
        return originalTimeslotId;
    }

    public boolean isLocked() {
        return locked;
    }

    public Timeslot getTimeslot() {
        return timeslot;
    }

    public void setTimeslot(Timeslot timeslot) {
        this.timeslot = timeslot;
    }

    public boolean isChangedFromOriginal() {
        return originalTimeslotId != null && timeslot != null && !originalTimeslotId.equals(timeslot.getId());
    }

    @Override
    public String toString() {
        return studentGroup + "-" + subject + "(" + teacher + ")";
    }
}
