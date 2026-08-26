package com.temperature.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.DayOfWeek;
import java.time.Duration;
import java.util.List;

import com.temperature.timetable.domain.Lesson;
import com.temperature.timetable.domain.Timeslot;
import com.temperature.timetable.domain.Timetable;
import org.junit.jupiter.api.Test;

class TemperatureTimetableApplicationTest {

    @Test
    void movesOnlyTheConflictingUnlockedLesson() {
        Timeslot monday1 = new Timeslot("MON-1", DayOfWeek.MONDAY, 1);
        Timeslot monday2 = new Timeslot("MON-2", DayOfWeek.MONDAY, 2);
        Timeslot monday3 = new Timeslot("MON-3", DayOfWeek.MONDAY, 3);

        Lesson pinned = new Lesson("L1", "数学", "张老师", "二1", "MON-1", true, monday1);
        Lesson movable = new Lesson("L2", "语文", "张老师", "三1", "MON-1", false, monday1);

        Timetable problem = new Timetable(
                List.of(monday1, monday2, monday3),
                List.of(),
                List.of(pinned, movable));

        Timetable solution = TemperatureTimetableApplication.solve(problem, Duration.ofSeconds(1));

        assertNotNull(solution.getScore());
        assertEquals("MON-1", pinned.getTimeslot().getId(), "Pinned lesson must stay in place");
        assertNotEquals("MON-1", movable.getTimeslot().getId(), "Conflicting lesson must move");
    }
}
