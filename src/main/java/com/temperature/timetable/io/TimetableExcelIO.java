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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.temperature.timetable.domain.Lesson;
import com.temperature.timetable.domain.TeacherUnavailable;
import com.temperature.timetable.domain.Timeslot;
import com.temperature.timetable.domain.Timetable;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public final class TimetableExcelIO {

    private static final DataFormatter FORMATTER = new DataFormatter();

    private TimetableExcelIO() {
    }

    public static Timetable read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(in)) {
            List<Timeslot> timeslots = readTimeslots(requiredSheet(workbook, "Timeslots"));
            Map<String, Timeslot> timeslotById = new HashMap<>();
            for (Timeslot timeslot : timeslots) {
                timeslotById.put(timeslot.getId(), timeslot);
            }

            List<Lesson> lessons = readLessons(requiredSheet(workbook, "Lessons"), timeslotById);
            Sheet unavailableSheet = workbook.getSheet("TeacherUnavailable");
            List<TeacherUnavailable> unavailable = unavailableSheet == null
                    ? List.of()
                    : readTeacherUnavailable(unavailableSheet, timeslotById);
            return new Timetable(timeslots, unavailable, lessons);
        }
    }

    public static void write(Timetable timetable, Path path) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Schedule");
            Row header = sheet.createRow(0);
            String[] headers = {"class", "subject", "teacher", "day", "period", "timeslotId", "changed"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);

            List<Lesson> lessons = new ArrayList<>(timetable.getLessons());
            lessons.sort(Comparator
                    .comparing(Lesson::getStudentGroup)
                    .thenComparing(l -> l.getTimeslot().getDayOfWeek().getValue())
                    .thenComparing(l -> l.getTimeslot().getPeriod()));

            int rowIndex = 1;
            for (Lesson lesson : lessons) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(lesson.getStudentGroup());
                row.createCell(1).setCellValue(lesson.getSubject());
                row.createCell(2).setCellValue(lesson.getTeacher());
                row.createCell(3).setCellValue(toChineseDay(lesson.getTimeslot().getDayOfWeek()));
                row.createCell(4).setCellValue(lesson.getTimeslot().getPeriod());
                row.createCell(5).setCellValue(lesson.getTimeslot().getId());
                row.createCell(6).setCellValue(lesson.isChangedFromOriginal());
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);

            try (OutputStream out = Files.newOutputStream(path)) {
                workbook.write(out);
            }
        }
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
