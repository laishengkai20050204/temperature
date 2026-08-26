package com.temperature.timetable.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.temperature.timetable.domain.Lesson;
import com.temperature.timetable.domain.TeacherUnavailable;
import com.temperature.timetable.domain.Timeslot;
import com.temperature.timetable.domain.Timetable;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class TimetableExcelIO {

    private static final DataFormatter FORMATTER = new DataFormatter();
    private static final List<String> SCHOOL_GROUPS = List.of("二1", "三1", "四1", "五1", "六1", "六2");
    private static final int[] PERIOD_ROW_INDEX = {2, 3, 4, 6, 7, 8};

    private TimetableExcelIO() {
    }

    public static boolean isSchoolWorkbook(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(in)) {
            return workbook.getSheet("排课说明") != null && workbook.getSheet("二1课表") != null;
        }
    }

    public static Timetable read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getSheet("Timeslots") != null && workbook.getSheet("Lessons") != null) {
                return readStandardWorkbook(workbook);
            }
            if (workbook.getSheet("排课说明") != null && workbook.getSheet("二1课表") != null) {
                return readSchoolWorkbook(workbook);
            }
            throw new IllegalArgumentException("Unsupported workbook format. Expected either standard sheets or the school timetable workbook.");
        }
    }

    private static Timetable readStandardWorkbook(Workbook workbook) {
        List<Timeslot> timeslots = readTimeslots(requiredSheet(workbook, "Timeslots"));
        Map<String, Timeslot> timeslotById = indexTimeslots(timeslots);
        List<Lesson> lessons = readLessons(requiredSheet(workbook, "Lessons"), timeslotById);
        Sheet unavailableSheet = workbook.getSheet("TeacherUnavailable");
        List<TeacherUnavailable> unavailable = unavailableSheet == null
                ? List.of()
                : readTeacherUnavailable(unavailableSheet, timeslotById);
        return new Timetable(timeslots, unavailable, lessons);
    }

    private static Timetable readSchoolWorkbook(Workbook workbook) {
        List<Timeslot> timeslots = buildSchoolTimeslots();
        Map<String, Timeslot> timeslotById = indexTimeslots(timeslots);
        List<Lesson> lessons = new ArrayList<>();

        for (String group : SCHOOL_GROUPS) {
            Sheet sheet = requiredSheet(workbook, group + "课表");
            for (int period = 1; period <= 6; period++) {
                Row row = sheet.getRow(PERIOD_ROW_INDEX[period - 1]);
                for (int dayIndex = 1; dayIndex <= 5; dayIndex++) {
                    String value = text(row, dayIndex);
                    if (value.isBlank() || value.equals("不上课")) continue;

                    ParsedLesson parsed = parseSchoolLesson(value, group, dayIndex, period);
                    DayOfWeek day = DayOfWeek.of(dayIndex);
                    String slotId = schoolTimeslotId(day, period);
                    Timeslot timeslot = timeslotById.get(slotId);
                    String lessonId = group + "-" + dayIndex + "-" + period;
                    lessons.add(new Lesson(lessonId, parsed.subject(), parsed.teacher(), group,
                            slotId, false, timeslot));
                }
            }
        }

        List<TeacherUnavailable> unavailable = buildSchoolTeacherUnavailable(lessons, timeslotById);
        return new Timetable(timeslots, unavailable, lessons);
    }

    private static List<TeacherUnavailable> buildSchoolTeacherUnavailable(List<Lesson> lessons,
                                                                           Map<String, Timeslot> timeslotById) {
        Set<String> chineseTeachers = new HashSet<>();
        Set<String> mathTeachers = new HashSet<>();
        Set<String> englishTeachers = new HashSet<>();
        for (Lesson lesson : lessons) {
            switch (lesson.getSubject()) {
                case "语文" -> chineseTeachers.add(lesson.getTeacher());
                case "数学" -> mathTeachers.add(lesson.getTeacher());
                case "英语" -> englishTeachers.add(lesson.getTeacher());
                default -> {
                }
            }
        }

        List<TeacherUnavailable> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 语文教研：周二下午第2、3节不排课。
        for (String teacher : chineseTeachers) {
            addUnavailable(result, seen, teacher, DayOfWeek.TUESDAY, 5, timeslotById);
            addUnavailable(result, seen, teacher, DayOfWeek.TUESDAY, 6, timeslotById);
        }

        // 数学教研：周四下午第2、3节不排课。
        for (String teacher : mathTeachers) {
            addUnavailable(result, seen, teacher, DayOfWeek.THURSDAY, 5, timeslotById);
            addUnavailable(result, seen, teacher, DayOfWeek.THURSDAY, 6, timeslotById);
        }

        // 姚金钗周一上午不排课。
        for (int period = 1; period <= 3; period++) {
            addUnavailable(result, seen, "姚金钗", DayOfWeek.MONDAY, period, timeslotById);
        }

        // 英语专任只允许周二、周四下午。
        for (String teacher : englishTeachers) {
            for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
                for (int period = 1; period <= 6; period++) {
                    boolean allowed = (day == DayOfWeek.TUESDAY || day == DayOfWeek.THURSDAY) && period >= 4;
                    if (!allowed) addUnavailable(result, seen, teacher, day, period, timeslotById);
                }
            }
        }

        // 林燕慧音乐只排周一、周五下午。
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            for (int period = 1; period <= 6; period++) {
                boolean allowed = (day == DayOfWeek.MONDAY || day == DayOfWeek.FRIDAY) && period >= 4;
                if (!allowed) addUnavailable(result, seen, "林燕慧", day, period, timeslotById);
            }
        }

        // 柯冬梅“版筑上”实际占用：周一/周二/周四下午第2、3节。
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)) {
            addUnavailable(result, seen, "柯冬梅", day, 5, timeslotById);
            addUnavailable(result, seen, "柯冬梅", day, 6, timeslotById);
        }

        return result;
    }

    private static void addUnavailable(List<TeacherUnavailable> result, Set<String> seen, String teacher,
                                       DayOfWeek day, int period, Map<String, Timeslot> timeslotById) {
        Timeslot slot = timeslotById.get(schoolTimeslotId(day, period));
        String key = teacher + "@" + slot.getId();
        if (seen.add(key)) result.add(new TeacherUnavailable(teacher, slot));
    }

    private static List<Timeslot> buildSchoolTimeslots() {
        List<Timeslot> result = new ArrayList<>(30);
        for (DayOfWeek day : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)) {
            for (int period = 1; period <= 6; period++) {
                result.add(new Timeslot(schoolTimeslotId(day, period), day, period));
            }
        }
        return result;
    }

    private static String schoolTimeslotId(DayOfWeek day, int period) {
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

    private static ParsedLesson parseSchoolLesson(String value, String group, int dayIndex, int period) {
        String normalized = value.replace('\r', '\n').trim();
        String subject = normalized.split("\\n", 2)[0].trim();
        int left = normalized.lastIndexOf('（');
        int right = normalized.lastIndexOf('）');
        if (left < 0 || right <= left) {
            left = normalized.lastIndexOf('(');
            right = normalized.lastIndexOf(')');
        }
        if (left < 0 || right <= left) {
            throw new IllegalArgumentException("Cannot parse teacher from " + group + " timetable at day "
                    + dayIndex + ", period " + period + ": " + value);
        }
        String teacher = normalized.substring(left + 1, right).trim();
        return new ParsedLesson(subject, teacher);
    }

    private record ParsedLesson(String subject, String teacher) {
    }

    public static void write(Timetable timetable, Path path) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Schedule");
            Row header = sheet.createRow(0);
            String[] headers = {"class", "subject", "teacher", "day", "period", "timeslotId", "changed"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

            List<Lesson> lessons = sortedLessons(timetable);
            int rowIndex = 1;
            for (Lesson lesson : lessons) {
                Row row = sheet.createRow(rowIndex++);
                writeResultRow(row, lesson);
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            try (OutputStream out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
    }

    public static void writeSchoolWorkbook(Path input, Timetable timetable, Path output) throws IOException {
        try (InputStream in = Files.newInputStream(input); Workbook workbook = WorkbookFactory.create(in)) {
            rewriteClassTimetables(workbook, timetable);
            rewriteTeacherTimetables(workbook, timetable);
            writeSolverResultSheet(workbook, timetable);
            try (OutputStream out = Files.newOutputStream(output)) {
                workbook.write(out);
            }
        }
    }

    private static void rewriteClassTimetables(Workbook workbook, Timetable timetable) {
        for (String group : SCHOOL_GROUPS) {
            Sheet sheet = requiredSheet(workbook, group + "课表");
            clearScheduleGrid(sheet);
        }

        for (Lesson lesson : timetable.getLessons()) {
            Sheet sheet = requiredSheet(workbook, lesson.getStudentGroup() + "课表");
            Cell cell = scheduleCell(sheet, lesson.getTimeslot());
            cell.setCellValue(lesson.getSubject() + "\n（" + lesson.getTeacher() + "）");
        }

        Sheet grade2 = requiredSheet(workbook, "二1课表");
        for (int dayColumn = 2; dayColumn <= 5; dayColumn++) {
            Row row = grade2.getRow(PERIOD_ROW_INDEX[5]);
            row.getCell(dayColumn, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue("不上课");
        }
    }

    private static void rewriteTeacherTimetables(Workbook workbook, Timetable timetable) {
        Set<String> teachers = new HashSet<>();
        for (Lesson lesson : timetable.getLessons()) teachers.add(lesson.getTeacher());

        for (String teacher : teachers) {
            Sheet sheet = workbook.getSheet(teacher);
            if (sheet != null) clearScheduleGrid(sheet);
        }

        Map<String, List<Lesson>> byTeacherSlot = new LinkedHashMap<>();
        for (Lesson lesson : sortedLessons(timetable)) {
            String key = lesson.getTeacher() + "@" + lesson.getTimeslot().getId();
            byTeacherSlot.computeIfAbsent(key, ignored -> new ArrayList<>()).add(lesson);
        }

        for (List<Lesson> sameSlot : byTeacherSlot.values()) {
            Lesson first = sameSlot.get(0);
            Sheet sheet = workbook.getSheet(first.getTeacher());
            if (sheet == null) continue;
            StringBuilder value = new StringBuilder();
            for (Lesson lesson : sameSlot) {
                if (!value.isEmpty()) value.append('\n');
                value.append(lesson.getStudentGroup()).append(' ').append(lesson.getSubject());
            }
            scheduleCell(sheet, first.getTimeslot()).setCellValue(value.toString());
        }
    }

    private static void clearScheduleGrid(Sheet sheet) {
        for (int rowIndex : PERIOD_ROW_INDEX) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) row = sheet.createRow(rowIndex);
            for (int column = 1; column <= 5; column++) {
                row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setBlank();
            }
        }
    }

    private static Cell scheduleCell(Sheet sheet, Timeslot timeslot) {
        int rowIndex = PERIOD_ROW_INDEX[timeslot.getPeriod() - 1];
        int columnIndex = timeslot.getDayOfWeek().getValue();
        Row row = sheet.getRow(rowIndex);
        if (row == null) row = sheet.createRow(rowIndex);
        return row.getCell(columnIndex, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
    }

    private static void writeSolverResultSheet(Workbook workbook, Timetable timetable) {
        int oldIndex = workbook.getSheetIndex("程序求解结果");
        if (oldIndex >= 0) workbook.removeSheetAt(oldIndex);
        Sheet sheet = workbook.createSheet("程序求解结果");
        Row summary = sheet.createRow(0);
        summary.createCell(0).setCellValue("Timefold求解分数");
        summary.createCell(1).setCellValue(String.valueOf(timetable.getScore()));
        summary.createCell(2).setCellValue("变动课程数");
        long changed = timetable.getLessons().stream().filter(Lesson::isChangedFromOriginal).count();
        summary.createCell(3).setCellValue(changed);

        Row header = sheet.createRow(2);
        String[] headers = {"班级", "课程", "教师", "星期", "节次", "时段ID", "是否变动", "原时段"};
        for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

        int rowIndex = 3;
        for (Lesson lesson : sortedLessons(timetable)) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(lesson.getStudentGroup());
            row.createCell(1).setCellValue(lesson.getSubject());
            row.createCell(2).setCellValue(lesson.getTeacher());
            row.createCell(3).setCellValue(toChineseDay(lesson.getTimeslot().getDayOfWeek()));
            row.createCell(4).setCellValue(lesson.getTimeslot().getPeriod());
            row.createCell(5).setCellValue(lesson.getTimeslot().getId());
            row.createCell(6).setCellValue(lesson.isChangedFromOriginal() ? "是" : "否");
            row.createCell(7).setCellValue(lesson.getOriginalTimeslotId() == null ? "" : lesson.getOriginalTimeslotId());
        }
        for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
    }

    private static List<Lesson> sortedLessons(Timetable timetable) {
        List<Lesson> lessons = new ArrayList<>(timetable.getLessons());
        lessons.sort(Comparator
                .comparing(Lesson::getStudentGroup)
                .thenComparing(l -> l.getTimeslot().getDayOfWeek().getValue())
                .thenComparing(l -> l.getTimeslot().getPeriod()));
        return lessons;
    }

    private static void writeResultRow(Row row, Lesson lesson) {
        row.createCell(0).setCellValue(lesson.getStudentGroup());
        row.createCell(1).setCellValue(lesson.getSubject());
        row.createCell(2).setCellValue(lesson.getTeacher());
        row.createCell(3).setCellValue(toChineseDay(lesson.getTimeslot().getDayOfWeek()));
        row.createCell(4).setCellValue(lesson.getTimeslot().getPeriod());
        row.createCell(5).setCellValue(lesson.getTimeslot().getId());
        row.createCell(6).setCellValue(lesson.isChangedFromOriginal());
    }

    private static Map<String, Timeslot> indexTimeslots(List<Timeslot> timeslots) {
        Map<String, Timeslot> result = new HashMap<>();
        for (Timeslot timeslot : timeslots) result.put(timeslot.getId(), timeslot);
        return result;
    }

    private static List<Timeslot> readTimeslots(Sheet sheet) {
        List<Timeslot> result = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, 0)) continue;
            String id = text(row, 0);
            DayOfWeek day = parseDay(text(row, 1));
            int period = Integer.parseInt(text(row, 2));
            result.add(new Timeslot(id, day, period));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Timeslots sheet is empty.");
        return result;
    }

    private static List<Lesson> readLessons(Sheet sheet, Map<String, Timeslot> timeslotById) {
        List<Lesson> result = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, 0)) continue;
            String id = text(row, 0);
            String subject = text(row, 1);
            String teacher = text(row, 2);
            String studentGroup = text(row, 3);
            String originalId = text(row, 4);
            if (originalId.isBlank()) originalId = null;
            boolean locked = parseBoolean(text(row, 5));
            Timeslot initial = originalId == null ? null : timeslotById.get(originalId);
            if (originalId != null && initial == null) {
                throw new IllegalArgumentException("Unknown timeslot '" + originalId + "' for lesson " + id);
            }
            if (locked && initial == null) {
                throw new IllegalArgumentException("Locked lesson must have an original timeslot: " + id);
            }
            result.add(new Lesson(id, subject, teacher, studentGroup, originalId, locked, initial));
        }
        return result;
    }

    private static List<TeacherUnavailable> readTeacherUnavailable(Sheet sheet, Map<String, Timeslot> timeslotById) {
        List<TeacherUnavailable> result = new ArrayList<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (isBlank(row, 0)) continue;
            String teacher = text(row, 0);
            String timeslotId = text(row, 1);
            Timeslot timeslot = timeslotById.get(timeslotId);
            if (timeslot == null) throw new IllegalArgumentException("Unknown timeslot: " + timeslotId);
            result.add(new TeacherUnavailable(teacher, timeslot));
        }
        return result;
    }

    private static Sheet requiredSheet(Workbook workbook, String name) {
        Sheet sheet = workbook.getSheet(name);
        if (sheet == null) throw new IllegalArgumentException("Missing required sheet: " + name);
        return sheet;
    }

    private static String text(Row row, int column) {
        if (row == null || row.getCell(column) == null) return "";
        return FORMATTER.formatCellValue(row.getCell(column)).trim();
    }

    private static boolean isBlank(Row row, int column) {
        return text(row, column).isBlank();
    }

    private static boolean parseBoolean(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes")
                || normalized.equals("y") || normalized.equals("是") || normalized.equals("锁定");
    }

    private static DayOfWeek parseDay(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "MONDAY", "MON", "周一", "星期一" -> DayOfWeek.MONDAY;
            case "TUESDAY", "TUE", "周二", "星期二" -> DayOfWeek.TUESDAY;
            case "WEDNESDAY", "WED", "周三", "星期三" -> DayOfWeek.WEDNESDAY;
            case "THURSDAY", "THU", "周四", "星期四" -> DayOfWeek.THURSDAY;
            case "FRIDAY", "FRI", "周五", "星期五" -> DayOfWeek.FRIDAY;
            default -> throw new IllegalArgumentException("Unsupported day: " + value);
        };
    }

    private static String toChineseDay(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "周一";
            case TUESDAY -> "周二";
            case WEDNESDAY -> "周三";
            case THURSDAY -> "周四";
            case FRIDAY -> "周五";
            case SATURDAY -> "周六";
            case SUNDAY -> "周日";
        };
    }
}
