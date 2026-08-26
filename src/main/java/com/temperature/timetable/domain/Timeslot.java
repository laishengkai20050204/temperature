package com.temperature.timetable.domain;

import java.time.DayOfWeek;
import java.util.Objects;

import ai.timefold.solver.core.api.domain.common.PlanningId;

public final class Timeslot {

    @PlanningId
    private String id;
    private DayOfWeek dayOfWeek;
    private int period;

    public Timeslot() {
    }

    public Timeslot(String id, DayOfWeek dayOfWeek, int period) {
        this.id = id;
        this.dayOfWeek = dayOfWeek;
        this.period = period;
    }

    public String getId() {
        return id;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public int getPeriod() {
        return period;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Timeslot timeslot)) return false;
        return Objects.equals(id, timeslot.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id;
    }
}
