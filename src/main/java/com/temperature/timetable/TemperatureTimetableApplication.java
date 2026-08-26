package com.temperature.timetable;

import java.nio.file.Path;
import java.time.Duration;

import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.SolverConfig;
import com.temperature.timetable.domain.Lesson;
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
        Timetable solution = solve(problem, Duration.ofSeconds(20));

        if (schoolWorkbook) {
            TimetableExcelIO.writeSchoolWorkbook(input, solution, output);
        } else {
            TimetableExcelIO.write(solution, output);
        }

        long changed = solution.getLessons().stream().filter(Lesson::isChangedFromOriginal).count();
        System.out.println("Solved score: " + solution.getScore());
        System.out.println("Changed lessons: " + changed);
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
}
