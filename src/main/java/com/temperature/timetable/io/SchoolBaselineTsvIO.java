package com.temperature.timetable.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.temperature.timetable.domain.Lesson;
import com.temperature.timetable.domain.TeacherUnavailable;
import com.temperature.timetable.domain.Timeslot;
import com.temperature.timetable.domain.Timetable;

public final class SchoolBaselineTsvIO {

    private SchoolBaselineTsvIO() {
    }

    public static Timetable read(Path path) throws IOException {
        List<Timeslot> timeslots = buildTimeslots();
        Map<String, Timeslot> byId = new HashMap<>();
        for (Timeslot timeslot : timeslots) byId.put(timeslot.getId(), timeslot);

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<Lesson> lessons = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length != 5) {
                throw new IllegalArgumentException("Invalid TSV line " + (i + 1) + ": " + line);
            }
            String group = parts[0];
            String subject = parts[1];
            String teacher = parts[2];
            DayOfWeek day = parseDay(parts[3]);
            int period = Integer.parseInt(parts[4]);
            String slotId = slotId(day, period);
            // Keep the approved slot as originalTimeslotId for the large minimal-change penalty,
            // but leave the planning variable uninitialized. This lets Timefold's construction
            // heuristic build a hard-feasible timetable first instead of getting trapped while
            // trying to repair a now-infeasible old timetable one move at a time.
            lessons.add(new Lesson(group + "-" + day.getValue() + "-" + period,
                    subject, teacher, group, slotId, false, null));
        }

        return new Timetable(timeslots, buildTeacherUnavailable(lessons, byId), lessons);
    }

    private static List<TeacherUnavailable> buildTeacherUnavailable(List<Lesson> lessons,
                                                                     Map<String, Timeslot> byId) {
        Set<String> chineseTeachers = new HashSet<>();
        Set<String> mathTeachers = new HashSet<>();
        Set<String> englishTeachers = new HashSet<>();
        for (Lesson lesson : lessons) {
            if (lesson.getSubject().equals("语文")) chineseTeachers.add(lesson.getTeacher());
            if (lesson.getSubject().equals("数学")) mathTeachers.add(lesson.getTeacher());
            if (lesson.getSubject().equals("英语")) englishTeachers.add(lesson.getTeacher());
        }

        List<TeacherUnavailable> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String teacher : chineseTeachers) {
            add(result, seen, teacher, DayOfWeek.TUESDAY, 5, byId);
            add(result, seen, teacher, DayOfWeek.TUESDAY, 6, byId);
        }
        for (String teacher : mathTeachers) {
            add(result, seen, teacher, DayOfWeek.THURSDAY, 5, byId);
            add(result, seen, teacher, DayOfWeek.THURSDAY, 6, byId);
        }
        for (int period = 1; period <= 3; period++) {
            add(result, seen, "姚金钗", DayOfWeek.MONDAY, period, byId);
        }
        for (String teacher : englishTeachers) {
            for (DayOfWeek day : workDays()) {
                for (int period = 1; period <= 6; period++) {
                    boolean allowed = (day == DayOfWeek.TUESDAY || day == DayOfWeek.THURSDAY) && period >= 4;
                    if (!allowed) add(result, seen, teacher, day, period, byId);
                }
            }
        }
        for (DayOfWeek day : workDays()) {
            for (int period = 1; period <= 6; period++) {
                boolean allowed = (day == DayOfWeek.MONDAY || day == DayOfWeek.FRIDAY) && period >= 4;
                if (!allowed) add(result, seen, "林燕慧", day, period, byId);
            }
        }
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)) {
            add(result, seen, "柯冬梅", day, 5, byId);
            add(result, seen, "柯冬梅", day, 6, byId);
        }
        return result;
    }

    private static void add(List<TeacherUnavailable> result, Set<String> seen, String teacher,
                            DayOfWeek day, int period, Map<String, Timeslot> byId) {
        Timeslot slot = byId.get(slotId(day, period));
        String key = teacher + "@" + slot.getId();
        if (seen.add(key)) result.add(new TeacherUnavailable(teacher, slot));
    }

    private static List<Timeslot> buildTimeslots() {
        List<Timeslot> result = new ArrayList<>(30);
        for (DayOfWeek day : workDays()) {
            for (int period = 1; period <= 6; period++) {
                result.add(new Timeslot(slotId(day, period), day, period));
            }
        }
        return result;
    }

    private static List<DayOfWeek> workDays() {
        return List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
    }

    private static DayOfWeek parseDay(String value) {
        return switch (value) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            default -> throw new IllegalArgumentException("Unsupported day: " + value);
        };
    }

    private static String slotId(DayOfWeek day, int period) {
        String prefix = switch (day) {
            case MONDAY -> "MON";
            case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED";
            case THURSDAY -> "THU";
            case FRIDAY -> "FRI";
            default -> throw new IllegalArgumentException("School timetable only supports Monday-Friday.");
        };
        return prefix + "-" + period;
    }
}
